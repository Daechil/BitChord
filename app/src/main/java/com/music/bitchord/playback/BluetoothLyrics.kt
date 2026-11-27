package com.music.bitchord.playback

import android.content.Context
import android.media.AudioManager
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.lockscreen.FloatingLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KO: the playing lyric line in the title's place while the sound goes out
 * over Bluetooth, for a car or speaker that shows only title, artist and album.
 *
 * Bluetooth has no field for lyrics, and the media session's subtitle — where
 * the line already goes — is not one a car reads. So the line takes the
 * title, and the song's own title moves into the artist line beside its
 * artist. Only while playing to a Bluetooth device: paused, between lines,
 * on the phone's speaker or casting, the song's own title is back.
 *
 * Android keeps one media session, so the notification and the lock screen's
 * media card show the same while it lasts; the app's own screens, Last.fm and
 * Discord read the queue and are untouched. Settings says so.
 *
 * Translations are the player's, never asked for here — as on the floating
 * bar, from [FloatingLyrics.translationsFor].
 */
object BluetoothLyrics {

    /** What the session shows in place of the song's own title and artist. */
    data class Shown(val videoId: String, val title: String, val artist: String)

    /** How long an answer to "is the sound going out over Bluetooth" is kept. */
    private const val ROUTE_CHECK_MS = 3_000L

    /** A song with lines still untranslated is looked at again this often. */
    private const val TRANSLATION_RECHECK_MS = 15_000L

    private var scope: CoroutineScope? = null
    private var appContext: Context? = null

    private var routeCheckedAt = 0L
    private var routeIsBluetooth = false

    private var trackId: String? = null
    private var lines: List<LyricLine> = emptyList()
    private var translated: List<String?>? = null
    private var translationComplete = false
    private var lastTranslationCheck = 0L
    private var translationJob: Job? = null

    fun attach(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = owned
        // A translation just made or shown by the player: looked for at once.
        owned.launch {
            LyricsTranslation.translationsChanged.collect {
                if (!translationComplete) lastTranslationCheck = 0L
            }
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        translationJob = null
        trackId = null
        lines = emptyList()
        translated = null
    }

    /**
     * The title and artist to show for [song] at [positionMs], or null for its
     * own. Called on the service's half-second lyric tick, on the main thread.
     */
    fun shownFor(song: Song, songLines: List<LyricLine>?, positionMs: Long, playing: Boolean, casting: Boolean): Shown? {
        if (!AppSettings.bluetoothLyrics.value || !playing || casting) return null
        val context = appContext ?: return null
        if (songLines.isNullOrEmpty() || !bluetoothOut(context)) return null
        if (song.videoId != trackId || songLines !== lines) {
            trackId = song.videoId
            lines = songLines
            translated = null
            translationComplete = false
            lastTranslationCheck = 0L
            translationJob?.cancel()
            translationJob = null
        }
        val show = AppSettings.bluetoothLyricsShow.value
        if (show != AppSettings.BLUETOOTH_LYRICS_ORIGINAL && !translationComplete &&
            System.currentTimeMillis() - lastTranslationCheck > TRANSLATION_RECHECK_MS
        ) {
            lookUpTranslation(context)
        }
        // The player's offset: positive shows the words later, as in the player.
        val lyricsMs = positionMs - AppSettings.lyricsOffsetMs.value
        val index = songLines.indexOfLast { it.timeMs <= lyricsMs }
        val line = songLines.getOrNull(index)?.takeIf { !it.isGap && it.text.isNotBlank() } ?: return null
        val translation = translated?.getOrNull(index)?.takeIf { it.isNotBlank() && it != line.text }
        val text = when (show) {
            AppSettings.BLUETOOTH_LYRICS_ORIGINAL -> line.text
            AppSettings.BLUETOOTH_LYRICS_TRANSLATION -> translation ?: return null
            else -> translation ?: line.text
        }
        val artist = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · ")
        return Shown(song.videoId, text.trim(), artist)
    }

    /**
     * Whether the sound is going out to a Bluetooth device with a display worth
     * showing a line on, asked of Android at most every [ROUTE_CHECK_MS]: a car
     * or a speaker, or one the name and class say nothing about — not earbuds
     * or headphones, unless Settings says those too. On earbuds the line only
     * reached the phone's own notification and lock screen, in place of the title.
     */
    private fun bluetoothOut(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - routeCheckedAt < ROUTE_CHECK_MS) return routeIsBluetooth
        routeCheckedAt = now
        val manager = context.getSystemService(AudioManager::class.java)
        val active = manager?.let { runCatching { AudioRouting.outputs(it) }.getOrNull() }?.firstOrNull { it.isActive }
        routeIsBluetooth = active != null && active.kind == AudioRouting.Kind.BLUETOOTH &&
            (AppSettings.bluetoothLyricsEarphones.value || active.form !in WORN)
        return routeIsBluetooth
    }

    private val WORN = setOf(AudioRouting.Form.BUDS, AudioRouting.Form.HEADPHONES)

    private fun lookUpTranslation(context: Context) {
        val id = trackId ?: return
        val source = lines
        if (source.isEmpty() || translationJob?.isActive == true) return
        lastTranslationCheck = System.currentTimeMillis()
        translationJob = scope?.launch {
            val found = try {
                withContext(Dispatchers.IO) { FloatingLyrics.translationsFor(context, id, source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (found != null && trackId == id && lines === source) {
                translated = found
                translationComplete = source.indices.filter { source[it].text.isNotBlank() }.all { found[it] != null }
            }
        }
    }
}
