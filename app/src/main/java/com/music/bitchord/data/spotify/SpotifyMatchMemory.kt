package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.json.JSONObject

/**
 * KO: a Spotify track's YouTube Music version once settled — by the rules or by
 * Claude — or Claude's word that there is none, kept so reopening a playlist
 * neither asks Claude again nor searches YouTube Music again. The searches were
 * the costly part: a 74-song playlist reopened meant 74+ searches each time, and
 * YouTube turned this phone's searches away after a few rounds of that.
 *
 * A found version is trusted for [FOUND_TTL_MS] (an upload can be taken down);
 * "none" for [NONE_TTL_MS], so a song added to YouTube Music later is found.
 * Holds the newest [MAX_ENTRIES]; on this device only.
 */
object SpotifyMatchMemory {

    // v2: the first file also kept "none" for calls that failed and for searches YouTube
    // turned away, which left whole playlists grey for good; it is dropped, not read.
    private const val PREFS = "ko_spotify_matches_v2"
    private const val OLD_PREFS = "ko_spotify_matches"
    private const val MAX_ENTRIES = 3_000
    private const val NONE = "none"
    private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NONE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null
    private val AT = Regex("\"at\":(\\d+)")
    private const val MANUAL = "\"m\":true"

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { context.applicationContext.deleteSharedPreferences(OLD_PREFS) }
    }

    /**
     * Null when never asked; [Remembered.song] null when Claude found the song is not there,
     * and then [miss] says why, when that was kept.
     */
    class Remembered(val song: Song?, val miss: SpotifyClaudeMatch.Miss? = null)

    fun get(trackId: String): Remembered? {
        val raw = prefs?.getString(trackId, null) ?: return null
        val now = System.currentTimeMillis()
        // "none@<time>[@<why>:<seconds>]"; a bare "none" is from before times were kept, and is let go.
        if (raw.startsWith(NONE)) {
            val parts = raw.split('@')
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
            if (expired(at, now, NONE_TTL_MS)) return null
            val miss = parts.getOrNull(2)?.let { why ->
                val kind = runCatching { SpotifyClaudeMatch.MissKind.valueOf(why.substringBefore(':')) }.getOrNull()
                kind?.let { SpotifyClaudeMatch.Miss(it, why.substringAfter(':', "0").toIntOrNull() ?: 0) }
            }
            return Remembered(null, miss)
        }
        return runCatching {
            val o = JSONObject(raw)
            // A song picked by hand ("m") is the listener's answer, and kept for good.
            if (!o.optBoolean("m", false) && expired(o.optLong("at", 0L), now, FOUND_TTL_MS)) return null
            // KO: the first search result, taken with no Claude key: looked for again once
            // Claude can check it, so adding a key later is not undone by a month-old guess.
            if (o.optBoolean("g", false) && com.music.bitchord.data.settings.AppSettings.spotifyClaudeMatch.value &&
                com.music.bitchord.data.lyrics.LyricsTranslation.claudeKeyUsable) return null
            Remembered(
                Song(
                    videoId = o.getString("v"),
                    title = o.optString("t"),
                    artist = o.optString("a"),
                    thumbnailUrl = o.optString("th").ifBlank { null },
                    durationText = o.optString("d").ifBlank { null },
                    albumName = o.optString("al").ifBlank { null },
                    albumId = o.optString("ai").ifBlank { null },
                    artistId = o.optString("ar").ifBlank { null },
                    isVideo = o.optBoolean("iv", false),
                ),
            )
        }.getOrNull()
    }

    /**
     * KO: past its [ttl] — or kept "in the future": saved while the phone's clock ran ahead,
     * such an answer would otherwise never grow old once the clock was put right.
     */
    internal fun expired(at: Long, now: Long, ttl: Long): Boolean =
        at > now + CLOCK_SLACK_MS || now - at >= ttl

    private const val CLOCK_SLACK_MS = 60L * 60 * 1000

    /** Entries in the file, counted once and kept up to date, so a save need not read the file whole. */
    private var count = -1

    @Synchronized
    fun put(trackId: String, song: Song?, miss: SpotifyClaudeMatch.Miss? = null, manual: Boolean = false, guess: Boolean = false) {
        val p = prefs ?: return
        if (trackId.isBlank()) return
        val value = song?.let {
            JSONObject()
                .put("v", it.videoId).put("t", it.title).put("a", it.artist)
                .put("th", it.thumbnailUrl.orEmpty()).put("d", it.durationText.orEmpty())
                .put("al", it.albumName.orEmpty()).put("ai", it.albumId.orEmpty()).put("ar", it.artistId.orEmpty())
                .put("iv", it.isVideo)
                .put("at", System.currentTimeMillis())
                .put("m", manual)
                .put("g", guess)
                .toString()
        } ?: ("$NONE@${System.currentTimeMillis()}" + (miss?.let { "@${it.kind.name}:${it.seconds}" } ?: ""))
        if (count < 0) count = p.all.size
        if (!p.contains(trackId)) count++
        val editor = p.edit().putString(trackId, value)
        // Past the limit, some go: a SharedPreferences file is read whole at launch. Down to
        // nine in ten at once, so this happens seldom.
        if (count > MAX_ENTRIES) {
            val gone = evictionOrder(p.all.filterKeys { it != trackId }.mapValues { it.value as? String })
                .take(count - MAX_ENTRIES * 9 / 10)
            gone.forEach(editor::remove)
            count -= gone.size
        }
        editor.apply()
    }

    /**
     * KO: the keys to let go first when the file is full: the oldest first, by when each
     * was found; songs picked by hand last of all, being the listener's own answers.
     */
    internal fun evictionOrder(entries: Map<String, String?>): List<String> =
        entries.entries
            .map { (key, raw) ->
                // Read by pattern, not parsed: thousands of entries, and only two fields wanted.
                val found = raw?.startsWith("{") == true
                val at = if (found) AT.find(raw!!)?.groupValues?.get(1)?.toLongOrNull() else raw?.split('@')?.getOrNull(1)?.toLongOrNull()
                Triple(key, found && MANUAL in raw!!, at ?: 0L)
            }
            .sortedWith(compareBy<Triple<String, Boolean, Long>> { it.second }.thenBy { it.third })
            .map { it.first }
}
