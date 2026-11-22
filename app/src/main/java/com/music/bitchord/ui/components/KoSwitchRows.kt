package com.music.bitchord.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp


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

/**
 * "Check again", shown while Claude refuses the key: forgets the refusal, so
 * the next translation tries the key again — after a top-up, say. Sends
 * nothing itself.
 */
@androidx.compose.runtime.Composable
fun ClaudeKeyRecheckRow(title: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.AutoAwesome,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.claude_key_recheck_subtitle),
        onClick = {
            com.music.bitchord.data.lyrics.LyricsTranslation.clearClaudeKeyRejection()
            android.widget.Toast.makeText(
                context,
                context.getString(com.music.bitchord.R.string.claude_key_recheck_done),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        },
    )
}

/** The size of the translation and pronunciation lines under each lyric: small, normal, large. */
@androidx.compose.runtime.Composable
fun LyricsSubSizeRow(title: String) {
    val size by com.music.bitchord.data.settings.AppSettings.lyricsSubLineSize.collectAsStateWithLifecycle()
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Language,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.lyrics_sub_size_subtitle),
    )
    com.music.bitchord.ui.screens.SegmentedControl(
        options = listOf(
            androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.lyrics_sub_size_small),
            androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.lyrics_sub_size_normal),
            androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.lyrics_sub_size_large),
        ),
        selectedIndex = size.coerceIn(0, 2),
        onSelect = com.music.bitchord.data.settings.AppSettings::setLyricsSubLineSize,
        modifier = androidx.compose.ui.Modifier.padding(
            start = com.music.bitchord.ui.screens.TEXT_INSET,
            end = com.music.bitchord.ui.screens.ROW_INSET,
            bottom = 14.dp,
        ),
    )
}

/**
 * How much of Android's 25 MB Auto Backup this app's data takes, worked out
 * when the row is shown. Past the limit Android backs up nothing for the app.
 */
@androidx.compose.runtime.Composable
fun AutoBackupSizeRow(title: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bytes by androidx.compose.runtime.produceState<Long?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.bitchord.data.BackupBudget.estimate(context)
        }
    }
    val size = bytes
    val megabytes = size?.let { String.format(java.util.Locale.ROOT, "%.1f MB", it / (1024.0 * 1024.0)) }
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.FileUpload,
        title = title,
        subtitle = when {
            megabytes == null -> androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.auto_backup_size_counting)
            size >= com.music.bitchord.data.BackupBudget.LIMIT_BYTES * 9 / 10 ->
                androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.auto_backup_size_near, megabytes)
            else -> androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.auto_backup_size_subtitle, megabytes)
        },
    )
}

/**
 * "Song background": each song looked up on the web once, for its
 * translation and explanation; and, under it while it is on, whether to try
 * the whole web when the wikis have nothing. Shows how many are kept.
 */
@androidx.compose.runtime.Composable
fun SongBackgroundRows(title: String) {
    val on by com.music.bitchord.data.settings.AppSettings.songBackground.collectAsStateWithLifecycle()
    val widen by com.music.bitchord.data.settings.AppSettings.songBackgroundWiden.collectAsStateWithLifecycle()
    val stored = androidx.compose.runtime.produceState(0, on) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.bitchord.data.lyrics.SongBackgrounds.storedCount().first
        }
    }.value
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Language,
        title = title,
        subtitle = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.song_background_subtitle) +
            if (stored > 0) " · " + androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.song_background_stored, stored) else "",
        trailing = {
            IosSwitch(checked = on, onCheckedChange = com.music.bitchord.data.settings.AppSettings::setSongBackground)
        },
        onClick = { com.music.bitchord.data.settings.AppSettings.setSongBackground(!on) },
    )
    if (on) {
        com.music.bitchord.ui.screens.SettingsSubRow(
            title = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.song_background_widen),
            checked = widen,
            onCheckedChange = com.music.bitchord.data.settings.AppSettings::setSongBackgroundWiden,
        )
        val sonnet by com.music.bitchord.data.settings.AppSettings.songBackgroundSonnet.collectAsStateWithLifecycle()
        com.music.bitchord.ui.screens.SettingsSubRow(
            title = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.song_background_sonnet),
            checked = sonnet,
            onCheckedChange = com.music.bitchord.data.settings.AppSettings::setSongBackgroundSonnet,
        )
    }
}
