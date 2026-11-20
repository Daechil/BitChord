package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlayerWaveforms
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.LocalAudioSource
import com.music.bitchord.playback.smart.SeekWaveform
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The seek bar's waveforms: a song's own, worked out from its downloaded file
 * once and kept as [SeekWaveform.POINTS] bytes, or else the energy curve
 * Automix already stored when it analysed the song. Nothing is fetched and
 * nothing is analysed while the setting is off.
 *
 * Downloaded songs are worked through one at a time in the background, with a
 * pause between them so playback is never short of the processor; a song's
 * waveform goes when its download does.
 */
object Waveforms {
    private const val TAG = "BitChord"
    private const val DIRECTORY = "waveforms"
    /** Between two songs' analyses: the decoder is the heaviest thing the app runs. */
    private const val PAUSE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dir: File? = null
    private var automix: AnalysisStore? = null
    private var appContext: Context? = null
    private var working = false
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (dir != null) return
        val app = context.applicationContext
        appContext = app
        dir = File(app.filesDir, DIRECTORY)
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

    private fun fileOf(videoId: String): File? {
        val base = dir ?: return null
        val name = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(base, "$name.bin")
    }

    /** The waveform for [videoId]: its own, or Automix's energy curve, or null. */
    suspend fun load(videoId: String): FloatArray? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        fileOf(videoId)?.takeIf { it.isFile }?.let { file ->
            runCatching { file.readBytes() }.getOrNull()
                ?.takeIf { it.size >= 4 }
                ?.let { bytes -> return@withContext FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f } }
        }
        val analysis = runCatching { automix?.load(videoId) }.getOrNull() ?: return@withContext null
        val curve = analysis.energyCurve.takeIf { it.size >= 4 } ?: return@withContext null
        SeekWaveform.shape(FloatArray(curve.size) { curve[it].energy.toFloat().coerceAtLeast(0f) })
    }

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
                        id !in failed && fileOf(id)?.isFile == false
                    } ?: break
                    analyze(next)
                    delay(PAUSE_MS)
                }
            } finally {
                synchronized(this@Waveforms) { working = false }
            }
        }
    }

    private suspend fun analyze(videoId: String) = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext
        val target = fileOf(videoId) ?: return@withContext
        try {
            val uri = Downloads.savedUri(context, videoId)
            val source = uri?.let { LocalAudioSource.open(context.contentResolver, it) }
            if (source == null) {
                failed += videoId
                return@withContext
            }
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
            target.parentFile?.mkdirs()
            val bytes = ByteArray(levels.size) { (levels[it] * 255f).toInt().coerceIn(0, 255).toByte() }
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
            PlayerWaveforms.revision.value++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.d(TAG, "waveform: ${error.javaClass.simpleName}")
            failed += videoId
        }
    }

    /**
     * Waveforms of songs no longer downloaded. An empty list is not taken as
     * "everything was deleted": it is also what the list reads before it has
     * loaded, and a few hundred bytes a song left behind costs nothing.
     */
    private suspend fun forgetGone(saved: Set<String>) = withContext(Dispatchers.IO) {
        if (saved.isEmpty()) return@withContext
        val base = dir?.takeIf { it.isDirectory } ?: return@withContext
        val keep = saved.mapNotNull { fileOf(it)?.name }.toSet()
        var removed = false
        base.listFiles()?.forEach { file ->
            if (file.name !in keep) removed = file.delete() || removed
        }
        if (removed) PlayerWaveforms.revision.value++
    }
}
