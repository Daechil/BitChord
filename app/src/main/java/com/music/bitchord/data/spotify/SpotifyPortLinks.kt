package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * KO: which YouTube Music playlist a Spotify playlist was saved to, and the
 * songs already put in it — so saving again adds only what is new, and a grey
 * row picked by hand later joins the playlist too. On this device only.
 */
object SpotifyPortLinks {

    private const val PREFS = "ko_spotify_ports"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** [playlistId] without "VL"; [videoIds] the songs already in it, as this app put them. */
    data class Link(val playlistId: String, val title: String, val videoIds: Set<String>)

    fun get(spotifyPlaylistId: String): Link? {
        val raw = prefs?.getString(spotifyPlaylistId, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val ids = o.getJSONArray("ids")
            Link(o.getString("yt"), o.optString("title"), (0 until ids.length()).map { ids.getString(it) }.toSet())
        }.getOrNull()
    }

    fun put(spotifyPlaylistId: String, link: Link) {
        val o = JSONObject().put("yt", link.playlistId).put("title", link.title).put("ids", JSONArray(link.videoIds.toList()))
        prefs?.edit()?.putString(spotifyPlaylistId, o.toString())?.apply()
    }

    fun forget(spotifyPlaylistId: String) {
        prefs?.edit()?.remove(spotifyPlaylistId)?.apply()
    }

    fun added(spotifyPlaylistId: String, videoIds: Collection<String>) {
        val link = get(spotifyPlaylistId) ?: return
        put(spotifyPlaylistId, link.copy(videoIds = link.videoIds + videoIds))
    }
}
