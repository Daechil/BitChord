package com.music.bitchord.ui.components

import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.toMediaItem
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
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
        SpotifyPreview.stop()
        picking.value = null
        pick.viewModel.viewModelScope.launch {
            if (tapped(context, pick.song, pick.viewModel) != null) toast(context, R.string.spotify_row_found)
        }
    }

    internal fun pick(pick: Pick, chosen: Song) {
        SpotifyPreview.stop()
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

    internal fun notice(text: Int) {
        appContext?.let { toast(it, text) }
    }

    // KO: the notice bar rather than a toast, which Android 12+ cuts at two lines.
    private fun toast(context: Context, text: Int) = KoNotice.show(context.getString(text))
}

/**
 * KO: a candidate heard before it is picked — "is this the one?" is decided by ear, not by a title.
 *
 * The candidate is put after the playing song and played from a third of the way in (past the
 * intro); the song that was playing is remembered with its position. Stopping — another
 * candidate, a pick, or the window closing — takes the candidate out and puts the old song
 * back where it was, playing or paused as it was. Not in a listening party: it would change
 * everyone's music.
 */
internal object SpotifyPreview {
    val playing = MutableStateFlow<String?>(null)

    private var inserted: String? = null
    private var insertedAt = -1
    private var returnIndex = -1
    private var returnPositionMs = 0L
    private var wasPlaying = false

    fun toggle(song: Song) {
        if (playing.value == song.videoId) return stop()
        if (com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty) {
            SpotifyRowTap.notice(R.string.spotify_preview_party)
            return
        }
        val controller = com.music.bitchord.playback.PlayingRowTap.controller ?: return
        stop()
        returnIndex = if (controller.mediaItemCount > 0) controller.currentMediaItemIndex else -1
        returnPositionMs = controller.currentPosition
        wasPlaying = controller.playWhenReady
        val at = if (returnIndex >= 0) returnIndex + 1 else 0
        controller.addMediaItem(at, song.toMediaItem())
        val length = song.durationMillis()
        controller.seekTo(at, if (length > 0) length / 3 else 30_000L)
        controller.play()
        inserted = song.videoId
        insertedAt = at
        playing.value = song.videoId
    }

    fun stop() {
        val id = inserted ?: return
        inserted = null
        playing.value = null
        val controller = com.music.bitchord.playback.PlayingRowTap.controller ?: return
        // Where it was put, when it is still there: the same song may also sit elsewhere in the
        // queue, and that copy is the listener's own.
        val index = insertedAt.takeIf { it in 0 until controller.mediaItemCount && controller.getMediaItemAt(it).mediaId == id }
            ?: (0 until controller.mediaItemCount).firstOrNull { controller.getMediaItemAt(it).mediaId == id }
        if (index != null) controller.removeMediaItem(index)
        if (returnIndex >= 0 && returnIndex < controller.mediaItemCount) {
            controller.seekTo(returnIndex, returnPositionMs)
            controller.playWhenReady = wasPlaying
        } else {
            controller.pause()
        }
    }
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
    val videoLabel = stringResource(R.string.spotify_pick_video)
    // Whatever ends the window ends a preview with it, and the old song comes back.
    DisposableEffect(pick) { onDispose { SpotifyPreview.stop() } }
    val previewing by SpotifyPreview.playing.collectAsState()
    val listening = stringResource(R.string.spotify_preview_listening)
    val tapToListen = stringResource(R.string.spotify_preview_hint)
    val choose = stringResource(R.string.spotify_pick_choose)
    KoWindow(title = stringResource(R.string.spotify_pick_title), onBack = { SpotifyRowTap.picking.value = null }) {
        // The song, and why it is grey (the row's own words: "PinocchioP · 다른 버전만 있어요").
        windowText("${pick.song.title}\n${pick.song.artist}")
        windowActions(listOf(WindowButton(label = retry, onClick = { SpotifyRowTap.retry(pick) })))
        val found = candidates
        when {
            found == null -> windowText(loading, header = header)
            found.isEmpty() -> windowText(none, header = header)
            else -> windowCard(rows = found, header = header, footer = "$tapToListen $footer") { song ->
                CandidateRow(
                    song = song,
                    detail = listOfNotNull(
                        videoLabel.takeIf { song.isVideo },
                        song.artist.ifBlank { null },
                        song.albumName?.ifBlank { null },
                    ).joinToString(" · "),
                    listening = listening.takeIf { previewing == song.videoId },
                    choose = choose,
                    onListen = { SpotifyPreview.toggle(song) },
                    onChoose = { SpotifyRowTap.pick(pick, song) },
                )
            }
        }
    }
}

/** A candidate: tap to hear it, "선택" to take it. */
@androidx.compose.runtime.Composable
private fun CandidateRow(
    song: Song,
    detail: String,
    listening: String?,
    choose: String,
    onListen: () -> Unit,
    onChoose: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clickable(onClick = onListen)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = song.title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            )
            val line = listOfNotNull(listening, detail.ifBlank { null }).joinToString(" · ")
            if (line.isNotBlank()) {
                androidx.compose.material3.Text(
                    text = line,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = if (listening != null) androidx.compose.material3.MaterialTheme.colorScheme.primary
                    else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        song.durationText?.let {
            androidx.compose.material3.Text(
                text = it,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp),
            )
        }
        androidx.compose.material3.Text(
            text = choose,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onChoose)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
