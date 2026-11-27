package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.json.JSONObject

/**
 * KO: what Claude decided for a Spotify track — its YouTube Music version, or
 * that there is none — kept so reopening the playlist does not ask again.
 * Only Claude's answers are kept; the free rules simply run again. Holds the
 * newest [MAX_ENTRIES]; on this device only.
 */
object SpotifyMatchMemory {

    // v2: the first file also kept "none" for calls that failed and for searches YouTube
    // turned away, which left whole playlists grey for good; it is dropped, not read.
    private const val PREFS = "ko_spotify_matches_v2"
    private const val OLD_PREFS = "ko_spotify_matches"
    private const val MAX_ENTRIES = 3_000
    private const val NONE = "none"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { context.applicationContext.deleteSharedPreferences(OLD_PREFS) }
    }

    /** Null when never asked; [Remembered.song] null when Claude found the song is not there. */
    class Remembered(val song: Song?)

    fun get(trackId: String): Remembered? {
        val raw = prefs?.getString(trackId, null) ?: return null
        if (raw == NONE) return Remembered(null)
        return runCatching {
            val o = JSONObject(raw)
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
                .toString()
        } ?: NONE
        val editor = p.edit().putString(trackId, value)
        // Past the limit, the oldest-added go: a SharedPreferences file is read whole at launch.
        if (p.all.size >= MAX_ENTRIES) p.all.keys.take(p.all.size - MAX_ENTRIES + 1).forEach(editor::remove)
        editor.apply()
    }
}
