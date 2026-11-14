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
        if (context == null || !AppSettings.keepDownloadTranslations.value) return
        queue.trySend(Job(song, uri.toString()))
    }

    private suspend fun translate(context: Context, job: Job) {
        // The lyrics written into the file are the ones the player reads for
        // it offline, so they are the ones whose translation it will look for.
        val lines = EmbeddedLyrics.forUri(context, job.uri) ?: return
        if (lines.none { it.text.isNotBlank() }) return
        val result = LyricsTranslation.translate(job.song.videoId, lines, targetLanguage(context))
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
