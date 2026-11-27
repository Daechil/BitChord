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

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { context.applicationContext.deleteSharedPreferences(OLD_PREFS) }
    }

    /** Null when never asked; [Remembered.song] null when Claude found the song is not there. */
    class Remembered(val song: Song?)

    fun get(trackId: String): Remembered? {
        val raw = prefs?.getString(trackId, null) ?: return null
        val now = System.currentTimeMillis()
        // "none@<time>"; a bare "none" is from before times were kept, and is let go.
        if (raw.startsWith(NONE)) {
            val at = raw.substringAfter('@', "").toLongOrNull() ?: return null
            return if (now - at < NONE_TTL_MS) Remembered(null) else null
        }
        return runCatching {
            val o = JSONObject(raw)
            if (now - o.optLong("at", 0L) >= FOUND_TTL_MS) return null
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
                ),
            )
        }.getOrNull()
    }

    fun put(trackId: String, song: Song?) {
        val p = prefs ?: return
        if (trackId.isBlank()) return
        val value = song?.let {
            JSONObject()
                .put("v", it.videoId).put("t", it.title).put("a", it.artist)
                .put("th", it.thumbnailUrl.orEmpty()).put("d", it.durationText.orEmpty())
                .put("al", it.albumName.orEmpty()).put("ai", it.albumId.orEmpty()).put("ar", it.artistId.orEmpty())
                .put("at", System.currentTimeMillis())
                .toString()
        } ?: "$NONE@${System.currentTimeMillis()}"
        val editor = p.edit().putString(trackId, value)
        // Past the limit, some go: a SharedPreferences file is read whole at launch.
        // (getAll copies the whole map, so it is asked once.)
        val all = p.all
        if (all.size >= MAX_ENTRIES) all.keys.take(all.size - MAX_ENTRIES + 1).forEach(editor::remove)
        editor.apply()
    }
}
