package com.music.bitchord.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** "Also songs I've heard": waveforms worked out from the cache. Greyed out while the waveform is off. */
@androidx.compose.runtime.Composable
fun WaveformPlayedRow(title: String) {
    val waveform by com.music.bitchord.data.settings.AppSettings.waveformSeekBar.collectAsStateWithLifecycle()
    val on by com.music.bitchord.data.settings.AppSettings.waveformPlayed.collectAsStateWithLifecycle()
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.AutoAwesome,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.waveform_played_subtitle),
        enabled = waveform,
        trailing = {
            IosSwitch(
                checked = on && waveform,
                onCheckedChange = if (waveform) com.music.bitchord.data.settings.AppSettings::setWaveformPlayed else null,
            )
        },
        onClick = { com.music.bitchord.data.settings.AppSettings.setWaveformPlayed(!on) },
    )
}

/**
 * Settings' "Live Update" switch for translating ahead (Android 16+). When it
 * is on but the system has Live Updates off for this app, it says so, and a
 * tap opens the page where they are turned on.
 */
@androidx.annotation.RequiresApi(36)
@androidx.compose.runtime.Composable
fun AheadLiveUpdatesRow(title: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by com.music.bitchord.data.settings.AppSettings.aheadLiveUpdates.collectAsStateWithLifecycle()
    // Asked again each time the screen comes back: the listener may have just
    // changed it on the system's page.
    var resumed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val allowed = androidx.compose.runtime.remember(resumed) {
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()
        }.getOrDefault(true)
    }
    val openSystemPage = {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.AutoAwesome,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(
            if (on && !allowed) com.music.bitchord.R.string.ahead_live_updates_blocked else com.music.bitchord.R.string.ahead_live_updates_subtitle,
        ),
        trailing = {
            IosSwitch(checked = on, onCheckedChange = com.music.bitchord.data.settings.AppSettings::setAheadLiveUpdates)
        },
        onClick = {
            if (on && !allowed) openSystemPage() else com.music.bitchord.data.settings.AppSettings.setAheadLiveUpdates(!on)
        },
    )
}
