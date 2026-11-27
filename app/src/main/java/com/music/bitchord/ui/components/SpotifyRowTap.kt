package com.music.bitchord.ui.components

import android.content.Context
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * KO: a Spotify row with no YouTube Music version tapped. One still being
 * looked for says so; one marked not found is looked for again, and its
 * version handed back to be played when there now is one. Kept out of the
 * activity's composable, which is at the method-size limit.
 */
object SpotifyRowTap {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun tapped(context: Context, song: Song, viewModel: MainViewModel): Song? {
        // Play or Shuffle with nothing to play has just said so, and still lands on the
        // first row (the activity's function has no room for a branch of its own).
        if (System.currentTimeMillis() - nothingPlayableAt < 1_000) return null
        if (song.isMatchPending) {
            toast(context, R.string.spotify_row_still_matching)
            return null
        }
        toast(context, R.string.spotify_row_retrying)
        val found = viewModel.retrySpotifyRow(song)
        if (found == null) toast(context, R.string.spotify_row_still_missing)
        return found
    }

    /** A grey row tapped: looked for again in the background; found, its row turns playable. */
    fun tap(song: Song, viewModel: MainViewModel) {
        val context = appContext ?: return
        viewModel.viewModelScope.launch {
            if (tapped(context, song, viewModel) != null) toast(context, R.string.spotify_row_found)
        }
    }

    /**
     * Play: shuffle off for the queue it builds, and where it starts — the first
     * song that can play. When none can it says so, and leaves shuffle as it was.
     */
    fun playStart(songs: List<Song>): Int {
        val index = songs.indexOfFirst { !it.isUnresolvedSpotify }
        if (index < 0) return nothingPlayable()
        QueueShuffle.disableForNextQueue()
        return index
    }

    /** Shuffle: shuffle on, and any song that can play to lead; none, and shuffle is left alone. */
    fun shuffleStart(songs: List<Song>): Int {
        val index = songs.indices.filter { !songs[it].isUnresolvedSpotify }.randomOrNull() ?: return nothingPlayable()
        QueueShuffle.enableForNextQueue()
        return index
    }

    private var nothingPlayableAt = 0L

    private fun nothingPlayable(): Int {
        nothingPlayableAt = System.currentTimeMillis()
        appContext?.let { toast(it, R.string.spotify_nothing_playable) }
        return 0
    }

    private fun toast(context: Context, text: Int) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}
