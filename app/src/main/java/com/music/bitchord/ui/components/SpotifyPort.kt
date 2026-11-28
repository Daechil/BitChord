package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyPortLinks
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/**
 * KO: a Spotify playlist page saved as a YouTube Music playlist — from the
 * page's own matches, hand-picked songs included, in its order; saved before,
 * only the new songs go in. Opened from the page's + button (DetailScreen),
 * hosted in [KoHealthAlerts], so the activity's own function stays as it is.
 */
object SpotifyPort {
    private var model: WeakReference<MainViewModel>? = null

    /** The Spotify page asking to be saved; null when the window is closed. */
    internal val opening = MutableStateFlow<String?>(null)

    fun attach(viewModel: MainViewModel) {
        model = WeakReference(viewModel)
    }

    fun open(browseId: String) {
        if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) opening.value = browseId
    }

    internal fun viewModel(): MainViewModel? = model?.get()
}

@Composable
internal fun SpotifyPortWindow() {
    val browseId = SpotifyPort.opening.collectAsState().value ?: return
    val viewModel = SpotifyPort.viewModel() ?: return
    val stack by viewModel.detailStack.collectAsState()
    val page = stack.firstOrNull { it.browseId == browseId }
    if (page == null) {
        // The page was closed under the window: nothing left to save.
        LaunchedEffect(browseId) { SpotifyPort.opening.value = null }
        return
    }
    val songs = (page.songs as? UiState.Success<List<Song>>)?.data.orEmpty()
    val found = songs.filterNot { it.isUnresolvedSpotify }.map { it.videoId }.distinct()
    val missing = songs.count { it.videoId.startsWith(SPOTIFY_MISSING_PREFIX) && !it.videoId.startsWith(SPOTIFY_MISSING_PREFIX + "episode:") }
    // Still being looked for: counted by their own mark, not as "unresolved but not missing" —
    // a podcast row is neither, and would hold the save back for good.
    val pending = songs.count { it.isMatchPending }
    val link = remember(browseId, stack) { SpotifyPortLinks.get(browseId.removePrefix(SPOTIFY_PAGE_PREFIX)) }
    val fresh = link?.let { l -> found.count { it !in l.videoIds } }
    var saving by remember(browseId) { mutableStateOf(false) }
    fun save(privacy: PlaylistPrivacy) {
        saving = true
        viewModel.portSpotifyPage(browseId, privacy) { message ->
            saving = false
            SpotifyPort.opening.value = null
            KoNotice.show(message)
        }
    }
    val counts = stringResource(R.string.spotify_port_counts, found.size, missing)
    val pendingText = if (pending > 0) stringResource(R.string.spotify_port_pending, pending) else null
    val savingText = stringResource(R.string.spotify_port_saving)
    val footer = stringResource(R.string.spotify_port_footer)
    val linkedText = link?.let { stringResource(R.string.spotify_port_linked, it.title) }
    val syncLabel = fresh?.let { stringResource(R.string.spotify_port_sync, it) }
    val upToDate = stringResource(R.string.spotify_port_up_to_date)
    val retryAllLabel = stringResource(R.string.spotify_retry_all)
    val privateLabel = stringResource(R.string.spotify_port_private)
    val unlistedLabel = stringResource(R.string.spotify_port_unlisted)
    KoWindow(title = stringResource(R.string.spotify_port_title), onBack = { if (!saving) SpotifyPort.opening.value = null }) {
        windowText(listOfNotNull(page.title, counts, linkedText).joinToString("\n"))
        // Grey rows first, if any: worth one more look before saving without them.
        if (missing > 0 && pending == 0 && !saving) {
            windowActions(listOf(WindowButton(label = retryAllLabel, onClick = {
                SpotifyPort.opening.value = null
                viewModel.retryMissingSpotify(browseId)
            })))
        }
        when {
            saving -> windowText(savingText)
            pendingText != null -> windowText(pendingText)
            link != null && fresh == 0 -> windowText(upToDate)
            link != null -> windowActions(
                listOf(WindowButton(label = syncLabel.orEmpty(), onClick = { save(PlaylistPrivacy.PRIVATE) })),
                footer = footer,
            )
            else -> windowActions(
                listOf(
                    WindowButton(label = privateLabel, enabled = found.isNotEmpty(), onClick = { save(PlaylistPrivacy.PRIVATE) }),
                    WindowButton(label = unlistedLabel, enabled = found.isNotEmpty(), onClick = { save(PlaylistPrivacy.UNLISTED) }),
                ),
                footer = footer,
            )
        }
    }
}
