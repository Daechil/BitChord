package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.LocaleTags
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Translates a song's lyrics as it finishes downloading, and keeps the
 * translations of downloaded songs out of the cache.
 *
 * A downloaded song is the one played on a plane or underground, where a
 * translation could not be asked for; so it is asked for here, while there is
 * a connection, and filed in [LyricsTranslation]'s kept store, which the
 * system's cache clearing and the cache's own size limit never touch.
 *
 * One song at a time: an album downloads a dozen tracks within a minute, and
 * a dozen Claude requests at once would mostly come back rate-limited.
 */
object DownloadTranslations {
    private const val TAG = "DownloadTranslations"

    private class Job(val song: Song, val uri: String)

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        // The player's own translations of a downloaded song go to the kept
        // store too, so one translated before this existed — or before its
        // download — is kept from its next play.
        LyricsTranslation.keepTranslationFor = { trackId ->
            AppSettings.keepDownloadTranslations.value && Downloads.saved.value.containsKey(trackId)
        }
        // Songs gathered before the app last closed go up now.
        gatherScope.launch {
            // After the rest of the app has started: the job reads downloads and settings.
            kotlinx.coroutines.delay(30_000L)
            if (readWaiting(app).isNotEmpty()) flush(app)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (job in queue) {
                try {
                    translate(app, job)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "could not translate ${job.song.videoId}: $error")
                }
            }
        }
    }

    /** Called once a download is recorded; does nothing while the setting is off. */
    fun downloaded(song: Song, uri: Uri) {
        val app = context ?: return
        if (!AppSettings.keepDownloadTranslations.value) return
        // At half price, a while later: gathered and sent ahead as one batch.
        if (AppSettings.downloadTranslationsBatch.value && TranslateAhead.available()) {
            gather(app, song)
            return
        }
        queue.trySend(Job(song, uri.toString()))
    }

    // ---- Gathered for a batch ------------------------------------------------

    /** A downloaded song waiting to go up in the next batch. */
    @kotlinx.serialization.Serializable
    private data class Waiting(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationText: String? = null,
    )

    /** A batch goes up this long after the last download, or once a batch's worth (Settings' batch size) are waiting. */
    private const val GATHER_QUIET_MS = 3L * 60L * 1000L
    private const val WAITING_FILE = "translate_ahead/download_waiting.json"
    private val waitingSerializer = kotlinx.serialization.builtins.ListSerializer(Waiting.serializer())
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val gatherLock = Any()
    private val gatherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: kotlinx.coroutines.Job? = null

    private fun waitingFile(app: Context) = java.io.File(app.filesDir, WAITING_FILE)

    private fun readWaiting(app: Context): List<Waiting> =
        runCatching { json.decodeFromString(waitingSerializer, waitingFile(app).readText()) }.getOrDefault(emptyList())

    private fun writeWaiting(app: Context, list: List<Waiting>) {
        runCatching {
            val file = waitingFile(app)
            if (list.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(waitingSerializer, list))
        }
    }

    /** Kept on disk as they come, so songs downloaded just before the app closed still go. */
    private fun gather(app: Context, song: Song) {
        TranslationStatus.remember(song.videoId, song.title, song.artist)
        // One timer at a time: each download restarts it, under the same lock
        // as the list, so two finishing together cannot leave two behind.
        synchronized(gatherLock) {
            val list = readWaiting(app).filter { it.videoId != song.videoId } +
                Waiting(song.videoId, song.title, song.artist, song.albumName, song.durationText)
            writeWaiting(app, list)
            flushJob?.cancel()
            val full = list.size >= AppSettings.translateAheadChunk.value.coerceAtLeast(1)
            flushJob = gatherScope.launch {
                if (!full) kotlinx.coroutines.delay(GATHER_QUIET_MS)
                flush(app)
            }
        }
    }

    /** One batch being made at a time, from whichever timer or start-up pass got there first. */
    private val flushing = kotlinx.coroutines.sync.Mutex()

    /** Sends what has gathered as one batch, named for when it went. */
    private suspend fun flush(app: Context): Unit = flushing.withLock {
        // Taken off the list as they are read, so no other flush can send them too;
        // put back if the job could not start.
        val taken = synchronized(gatherLock) {
            val list = readWaiting(app)
            writeWaiting(app, emptyList())
            list
        }
        // Songs found to have no lyrics have nothing to send: dropped here, or a
        // list of only them could never start a job and would be put back for good.
        val songs = taken.filterNot { PlayedLyrics.isMissing(it.videoId) }
        if (songs.isEmpty()) return@withLock
        val title = app.getString(
            com.music.bitchord.R.string.translate_ahead_download_job,
            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date()),
        )
        val started = TranslateAhead.start(
            title = title,
            songs = songs.map {
                Song(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.artist,
                    thumbnailUrl = null,
                    durationText = it.durationText,
                    albumName = it.album,
                )
            },
            model = LyricsTranslation.claudeModel,
            reading = AppSettings.translateAheadReading.value,
        )
        if (!started) {
            synchronized(gatherLock) {
                val now = readWaiting(app)
                writeWaiting(app, songs.filter { old -> now.none { it.videoId == old.videoId } } + now)
            }
        }
    }

    private suspend fun translate(context: Context, job: Job) {
        // The lyrics written into the file are the ones the player reads for
        // it offline, so they are the ones whose translation it will look for.
        val lines = EmbeddedLyrics.forUri(context, job.uri) ?: return
        if (lines.none { it.text.isNotBlank() }) return
        com.music.bitchord.download.TranslationStatus.remember(job.song.videoId, job.song.title, job.song.artist)
        val result = LyricsTranslation.translate(
            job.song.videoId,
            lines,
            targetLanguage(context),
            via = LyricsTranslation.VIA_DOWNLOAD,
        )
        Log.d(TAG, "${job.song.videoId}: ${result::class.simpleName}")
    }

    /** The language the player translates into: Settings' choice, or the app's language. */
    fun targetLanguage(context: Context): String {
        AppSettings.translationLanguage.value.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val tag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()?.takeIf { it.isNotBlank() }
            ?: context.resources.configuration.locales.get(0).toLanguageTag()
        return if (LocaleTags.isChineseTag(tag)) {
            LocaleTags.translationWireTarget(tag)
        } else {
            Locale.forLanguageTag(tag).language.ifBlank { "en" }
        }
    }
}
