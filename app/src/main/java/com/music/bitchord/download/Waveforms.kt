package com.music.bitchord.download

import android.content.Context
import android.media.MediaDataSource
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PlayerWaveforms
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.LocalAudioSource
import com.music.bitchord.playback.smart.SeekWaveform
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The seek bar's waveforms: a song's own, worked out once and kept as
 * [SeekWaveform.POINTS] bytes, or else the energy curve Automix already
 * stored when it analysed the song. Nothing is fetched for this, and nothing
 * is analysed while the setting is off.
 *
 * Two kinds are kept apart, so that tidying one never touches the other:
 *  - **Downloaded songs**, from their files, worked through one at a time in
 *    the background with a pause between songs; a song's waveform goes when
 *    its download does.
 *  - **Songs heard** ([AppSettings.waveformPlayed]), from the cache, once
 *    the player's read-ahead has the whole song; the newest [MAX_PLAYED] are
 *    kept.
 */
object Waveforms {
    private const val TAG = "BitChord"
    private const val DOWNLOADED_DIRECTORY = "waveforms"
    private const val PLAYED_DIRECTORY = "waveforms_played"
    /** A few hundred kilobytes at most. */
    private const val MAX_PLAYED = 3_000
    /** Between two downloaded songs' analyses: the decoder is the heaviest thing the app runs. */
    private const val PAUSE_MS = 3_000L
    /** How long to wait for the playing song's read-ahead to finish, checking every [CACHE_CHECK_MS]. */
    private const val CACHE_WAIT_MS = 15L * 60L * 1000L
    private const val CACHE_CHECK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writing = Mutex()
    private var downloadedDir: File? = null
    private var playedDir: File? = null
    private var automix: AnalysisStore? = null
    private var appContext: Context? = null
    private var working = false
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var playingJob: Job? = null

    fun init(context: Context) {
        if (downloadedDir != null) return
        val app = context.applicationContext
        appContext = app
        downloadedDir = File(app.filesDir, DOWNLOADED_DIRECTORY)
        playedDir = File(app.filesDir, PLAYED_DIRECTORY)
        automix = AnalysisStore { app.filesDir }
        PlayerWaveforms.loader = ::load
        scope.launch {
            AppSettings.waveformSeekBar.collect { PlayerWaveforms.enabled.value = it }
        }
        scope.launch {
            combine(AppSettings.waveformSeekBar, Downloads.saved) { on, saved -> on to saved.keys }
                .distinctUntilChanged()
                .collect { (on, saved) ->
                    forgetGone(saved)
                    if (on) work()
                }
        }
    }

    private fun nameOf(videoId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32) + ".bin"

    private fun downloadedFile(videoId: String): File? = downloadedDir?.let { File(it, nameOf(videoId)) }
    private fun playedFile(videoId: String): File? = playedDir?.let { File(it, nameOf(videoId)) }

    private fun known(videoId: String): Boolean =
        downloadedFile(videoId)?.isFile == true || playedFile(videoId)?.isFile == true

    private fun read(file: File?): FloatArray? {
        val bytes = file?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: return null
        if (bytes.size < 4) return null
        return FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    /** The waveform for [videoId]: its own, or Automix's energy curve, or null. */
    suspend fun load(videoId: String): FloatArray? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        read(downloadedFile(videoId))?.let { return@withContext it }
        read(playedFile(videoId))?.let { return@withContext it }
        val analysis = runCatching { automix?.load(videoId) }.getOrNull() ?: return@withContext null
        val curve = analysis.energyCurve.takeIf { it.size >= 4 } ?: return@withContext null
        SeekWaveform.shape(FloatArray(curve.size) { curve[it].energy.toFloat().coerceAtLeast(0f) })
    }

    // ---- Downloaded songs ---------------------------------------------------

    /** Downloaded songs without a waveform yet, one after another, until none are left or the setting goes off. */
    private fun work() {
        synchronized(this) {
            if (working) return
            working = true
        }
        scope.launch {
            try {
                while (AppSettings.waveformSeekBar.value) {
                    val next = Downloads.saved.value.keys.firstOrNull { id ->
                        id !in failed && downloadedFile(id)?.isFile == false
                    } ?: break
                    analyzeDownloaded(next)
                    delay(PAUSE_MS)
                }
            } finally {
                synchronized(this@Waveforms) { working = false }
            }
        }
    }

    private suspend fun analyzeDownloaded(videoId: String) = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext
        val target = downloadedFile(videoId) ?: return@withContext
        val uri = try {
            Downloads.savedUri(context, videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val source = uri?.let { LocalAudioSource.open(context.contentResolver, it) }
        if (source == null) {
            failed += videoId
            return@withContext
        }
        analyze(videoId, source, target)
    }

    /**
     * Waveforms of songs no longer downloaded. An empty list is not taken as
     * "everything was deleted": it is also what the list reads before it has
     * loaded, and a few hundred bytes a song left behind costs nothing.
     */
    private suspend fun forgetGone(saved: Set<String>) = withContext(Dispatchers.IO) {
        if (saved.isEmpty()) return@withContext
        val base = downloadedDir?.takeIf { it.isDirectory } ?: return@withContext
        val keep = saved.map(::nameOf).toSet()
        var removed = false
        base.listFiles()?.forEach { file ->
            if (file.name !in keep) removed = file.delete() || removed
        }
        if (removed) PlayerWaveforms.revision.value++
    }

    // ---- Songs heard ------------------------------------------------------------

    /**
     * The player moved to [videoId]. A song with no waveform yet, not
     * downloaded, is worked out from the cache once the read-ahead has the
     * whole of it — usually within the first minute — and the bar changes
     * over as soon as it is done. Only the song playing is waited for: moving
     * on stops the wait. Nothing is fetched for this; a song never cached
     * whole simply keeps the plain bar.
     */
    fun playing(videoId: String?) {
        playingJob?.cancel()
        val id = videoId?.takeIf { it.isNotBlank() && !it.contains("://") } ?: return
        if (!AppSettings.waveformSeekBar.value || !AppSettings.waveformPlayed.value) return
        playingJob = scope.launch {
            if (known(id) || id in failed || id in Downloads.saved.value) return@launch
            val target = playedFile(id) ?: return@launch
            val waitUntil = System.currentTimeMillis() + CACHE_WAIT_MS
            while (System.currentTimeMillis() < waitUntil && AppSettings.waveformSeekBar.value && AppSettings.waveformPlayed.value) {
                val source = try {
                    AudioCache.completeAudioSource(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (source != null) {
                    analyze(id, source, target)
                    prunePlayed()
                    return@launch
                }
                delay(CACHE_CHECK_MS)
            }
        }
    }

    /** Only the newest [MAX_PLAYED] heard songs' waveforms are kept. */
    private suspend fun prunePlayed() = withContext(Dispatchers.IO) {
        val files = playedDir?.listFiles { file -> file.name.endsWith(".bin") } ?: return@withContext
        if (files.size <= MAX_PLAYED) return@withContext
        files.sortedByDescending { it.lastModified() }.drop(MAX_PLAYED).forEach { it.delete() }
    }

    // ---- Both ------------------------------------------------------------------

    /** Works out [videoId]'s waveform from [source], closes it, and keeps it at [target]. */
    private suspend fun analyze(videoId: String, source: MediaDataSource, target: File) = withContext(Dispatchers.IO) {
        try {
            val levels = try {
                Thread.currentThread().priority = Thread.MIN_PRIORITY
                SeekWaveform.levels(source) { !AppSettings.waveformSeekBar.value }
            } finally {
                Thread.currentThread().priority = Thread.NORM_PRIORITY
                runCatching { source.close() }
            }
            if (levels == null) {
                if (AppSettings.waveformSeekBar.value) failed += videoId
                return@withContext
            }
            val bytes = ByteArray(levels.size) { (levels[it] * 255f).toInt().coerceIn(0, 255).toByte() }
            writing.withLock {
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, target.name + ".tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    target.delete()
                    temporary.renameTo(target)
                }
            }
            PlayerWaveforms.revision.value++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.d(TAG, "waveform: ${error.javaClass.simpleName}")
            failed += videoId
        }
    }
}
