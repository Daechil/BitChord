package com.music.bitchord.ui.components

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.music.bitchord.data.model.Song

/**
 * The player's queue actions, for windows drawn outside the screen that owns
 * them. Set by the app's root composable while it is on screen; null otherwise,
 * and the windows then offer nothing that would need them.
 */
object KoQueue {
    /** Plays these, from the first, replacing the queue. */
    @Volatile var play: ((List<Song>, Int) -> Unit)? = null
    @Volatile var playNext: ((List<Song>) -> Unit)? = null
    @Volatile var addToQueue: ((List<Song>) -> Unit)? = null
    /** Saves these as a new playlist with this name. */
    @Volatile var savePlaylist: ((String, List<Song>) -> Unit)? = null
    /** The queue as it stands, current song first, for "from the queue". */
    @Volatile var upcoming: (() -> List<Song>)? = null
}

/**
 * Keeps [KoQueue] pointing at the root composable's current actions, and
 * clears it when that leaves the screen. One call from the root, so the root's
 * already very long body gains a line rather than the wiring.
 */
@androidx.compose.runtime.Composable
fun KoQueueHost(
    viewModel: com.music.bitchord.ui.MainViewModel,
    play: (List<Song>, Int) -> Unit,
    playNext: (List<Song>) -> Unit,
    addToQueue: (List<Song>) -> Unit,
    upcoming: () -> List<Song>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        KoQueue.play = play
        KoQueue.playNext = playNext
        KoQueue.addToQueue = addToQueue
        KoQueue.upcoming = upcoming
        KoQueue.savePlaylist = { name, songs ->
            viewModel.createPlaylistWithVideoIds(
                name,
                com.music.bitchord.data.model.PlaylistPrivacy.PRIVATE,
                songs.map { it.videoId },
                songs,
            ) { _, title, savedLocally ->
                // A toast rather than the app's own notice: the window asking
                // for this sits over the screen that notice is drawn on.
                android.widget.Toast.makeText(
                    context.applicationContext,
                    context.getString(
                        if (savedLocally) com.music.bitchord.R.string.recommend_saved_local else com.music.bitchord.R.string.recommend_saved,
                        title,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            KoQueue.play = null
            KoQueue.playNext = null
            KoQueue.addToQueue = null
            KoQueue.upcoming = null
            KoQueue.savePlaylist = null
        }
    }
}

/** Settings' "waveform seek bar" switch: its own composable, so Settings' long body gains a call rather than the row. */
@androidx.compose.runtime.Composable
fun WaveformSeekBarRow(title: String) {
    val on by com.music.bitchord.data.settings.AppSettings.waveformSeekBar.collectAsStateWithLifecycle()
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.AutoAwesome,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.waveform_seek_bar_subtitle),
        trailing = {
            IosSwitch(checked = on, onCheckedChange = com.music.bitchord.data.settings.AppSettings::setWaveformSeekBar)
        },
        onClick = { com.music.bitchord.data.settings.AppSettings.setWaveformSeekBar(!on) },
    )
}
