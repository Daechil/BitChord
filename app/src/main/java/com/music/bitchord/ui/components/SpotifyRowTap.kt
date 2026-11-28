package com.music.bitchord.ui.components

import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import com.music.bitchord.data.spotify.SpotifyTrack

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
        // Still being looked for, or Play/Shuffle with nothing to play: [tapped] says so.
        if (song.isMatchPending || System.currentTimeMillis() - nothingPlayableAt < 1_000) {
            viewModel.viewModelScope.launch { tapped(context, song, viewModel) }
            return
        }
        // Marked not found: the window — look again, or pick the song by hand.
        val track = viewModel.spotifyTrackFor(song) ?: return
        picking.value = Pick(song, track, viewModel)
    }

    /** A grey row being dealt with in [SpotifyPickWindow]. */
    internal class Pick(val song: Song, val track: SpotifyTrack, val viewModel: MainViewModel)

    internal val picking = MutableStateFlow<Pick?>(null)

    internal fun retry(pick: Pick) {
        val context = appContext ?: return
        picking.value = null
        pick.viewModel.viewModelScope.launch {
            if (tapped(context, pick.song, pick.viewModel) != null) toast(context, R.string.spotify_row_found)
        }
    }

    internal fun pick(pick: Pick, chosen: Song) {
        picking.value = null
        pick.viewModel.pickSpotifyRow(pick.track, chosen)
        appContext?.let { toast(it, R.string.spotify_pick_done) }
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

    // KO: the notice bar rather than a toast, which Android 12+ cuts at two lines.
    private fun toast(context: Context, text: Int) = KoNotice.show(context.getString(text))
}

/**
 * KO: a grey Spotify row tapped — "look again", or the YouTube Music results for
 * it, with their lengths beside the Spotify one, to pick from by hand. Hosted in
 * [KoHealthAlerts], so the activity's own function stays as it is.
 */
@androidx.compose.runtime.Composable
internal fun SpotifyPickWindow() {
    val pick = SpotifyRowTap.picking.collectAsState().value ?: return
    var candidates by remember(pick) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(pick) { candidates = pick.viewModel.spotifyCandidates(pick.track) }
    val length = pick.track.durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) } ?: "—"
    val retry = stringResource(R.string.spotify_pick_retry)
    val header = stringResource(R.string.spotify_pick_header, length)
    val footer = stringResource(R.string.spotify_pick_footer)
    val loading = stringResource(R.string.spotify_pick_loading)
    val none = stringResource(R.string.spotify_pick_none)
    KoWindow(title = stringResource(R.string.spotify_pick_title), onBack = { SpotifyRowTap.picking.value = null }) {
        // The song, and why it is grey (the row's own words: "PinocchioP · 다른 버전만 있어요").
        windowText("${pick.song.title}\n${pick.song.artist}")
        windowActions(listOf(WindowButton(label = retry, onClick = { SpotifyRowTap.retry(pick) })))
        val found = candidates
        when {
            found == null -> windowText(loading, header = header)
            found.isEmpty() -> windowText(none, header = header)
            else -> windowCard(rows = found, header = header, footer = footer) { song ->
                WindowLine(
                    title = song.title,
                    detail = listOfNotNull(song.artist.ifBlank { null }, song.albumName?.ifBlank { null }).joinToString(" · "),
                    value = song.durationText,
                    onClick = { SpotifyRowTap.pick(pick, song) },
                )
            }
        }
    }
}
