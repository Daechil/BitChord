package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.CrashRecord
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.PlaybackTrouble
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * Settings' "Check for updates": asks GitHub now, rather than waiting for the
 * next launch, and says what it found — up to date, a new build (opened, as
 * the launch check would), or why it could not tell.
 */
@Composable
fun UpdateCheckRow(title: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val current = BuildConfig.VERSION_NAME
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.FileDownload,
        title = title,
        subtitle = when {
            checking -> stringResource(R.string.update_checking)
            status != null -> status!!
            else -> stringResource(R.string.update_check_current, current) +
                if (BuildConfig.KO_BETA) " · " + stringResource(R.string.update_beta_badge) else ""
        },
        onClick = {
            if (checking) return@SettingsRow
            checking = true
            scope.launch {
                val result = AppUpdateChecker.check()
                checking = false
                status = when (result) {
                    AppUpdateChecker.CheckResult.UpToDate -> context.getString(R.string.update_up_to_date, current)
                    is AppUpdateChecker.CheckResult.Found -> {
                        KoAlerts.openUpdate.value = true
                        context.getString(R.string.update_found, result.info.version)
                    }
                    is AppUpdateChecker.CheckResult.Failed -> when (result.reason) {
                        AppUpdateChecker.FailReason.OFFLINE -> context.getString(R.string.update_failed_offline)
                        AppUpdateChecker.FailReason.RATE_LIMITED -> context.getString(R.string.update_failed_rate)
                        AppUpdateChecker.FailReason.NO_RELEASE -> context.getString(R.string.update_failed_none)
                        AppUpdateChecker.FailReason.HTTP -> context.getString(R.string.update_failed_http, result.httpCode ?: 0)
                        AppUpdateChecker.FailReason.UNREADABLE -> context.getString(R.string.update_failed_unreadable)
                    }
                }
            }
        },
    )
}

/**
 * Settings' "Get beta versions", under "Check for updates": a beta build
 * running now, with the switch turned off, is said to stay until a stable
 * build passes it — Android will not install an older version over it.
 */
@Composable
fun BetaUpdatesRow(title: String) {
    val on by com.music.bitchord.data.settings.AppSettings.betaUpdates.collectAsStateWithLifecycle()
    val runningBeta = BuildConfig.KO_BETA
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Upgrade,
        title = title,
        subtitle = stringResource(
            when {
                !on && runningBeta -> R.string.beta_updates_off_on_beta
                else -> R.string.beta_updates_subtitle
            },
        ),
        trailing = {
            com.music.bitchord.ui.components.IosSwitch(
                checked = on,
                onCheckedChange = com.music.bitchord.data.settings.AppSettings::setBetaUpdates,
            )
        },
        onClick = { com.music.bitchord.data.settings.AppSettings.setBetaUpdates(!on) },
    )
}

/**
 * The alerts about the app itself rather than the music: the update the
 * settings row found, playback stopped after several songs would not start,
 * and the last run's bad ending. Hosted from [ClaudeAlerts], so BitChordApp —
 * already at its method-size limit — gains nothing.
 */
@Composable
fun KoHealthAlerts(hazeState: HazeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val openUpdate by KoAlerts.openUpdate.collectAsStateWithLifecycle()
    val update by AppUpdateChecker.available.collectAsStateWithLifecycle()
    val info = update
    if (openUpdate && info != null) {
        val close = { KoAlerts.openUpdate.value = false }
        BackHandler(onBack = close)
        UpdateAvailableDialog(
            version = info.version,
            notes = info.notes,
            hazeState = hazeState,
            onDismiss = close,
            onDownload = {
                if (info.apkUrl != null) {
                    scope.launch { AppUpdateChecker.downloadApk(context) }
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    close()
                }
            },
            onCancelDownload = { AppUpdateChecker.cancelDownload() },
            onInstall = {
                (AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready)
                    ?.let { AppUpdateChecker.installApk(context, it.file) }
            },
            onOpenReleasePage = {
                AppUpdateChecker.resetDownload()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                close()
            },
        )
    }

    // KO: what this build brought, once after updating to it.
    WhatsNewAlerts(hazeState)
    SpotifyPickWindow()
    SpotifyPortWindow()

    val stopped by PlaybackTrouble.stopped.collectAsStateWithLifecycle()
    stopped?.let { trouble ->
        var checkLine by remember(trouble) { mutableStateOf<String?>(null) }
        HealthAlert(
            hazeState = hazeState,
            title = stringResource(R.string.playback_stopped_title),
            description = stringResource(
                if (trouble.network) R.string.playback_stopped_network else R.string.playback_stopped_youtube,
                trouble.songs,
            ) + (checkLine?.let { "\n\n$it" } ?: ""),
            actions = listOf(
                stringResource(R.string.copy_log) to {
                    scope.launch {
                        copyLog(context, TrackLog.recent(context.getString(R.string.playback_stopped_title)))
                    }
                    Unit
                },
                stringResource(R.string.update_check) to {
                    checkLine = context.getString(R.string.update_checking)
                    scope.launch {
                        checkLine = when (val result = AppUpdateChecker.check()) {
                            AppUpdateChecker.CheckResult.UpToDate -> context.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
                            is AppUpdateChecker.CheckResult.Found -> {
                                PlaybackTrouble.clear()
                                KoAlerts.openUpdate.value = true
                                context.getString(R.string.update_found, result.info.version)
                            }
                            is AppUpdateChecker.CheckResult.Failed -> context.getString(R.string.update_failed_offline)
                        }
                    }
                    Unit
                },
            ),
            onDismiss = { PlaybackTrouble.clear() },
        )
    }

    val crash by CrashRecord.pending.collectAsStateWithLifecycle()
    crash?.let { report ->
        val reason = stringResource(
            when (report.reason) {
                CrashRecord.Reason.CRASH -> R.string.crash_reason_crash
                CrashRecord.Reason.NATIVE -> R.string.crash_reason_native
                CrashRecord.Reason.ANR -> R.string.crash_reason_anr
                CrashRecord.Reason.LOW_MEMORY -> R.string.crash_reason_lmk
                CrashRecord.Reason.OTHER -> R.string.crash_reason_other
            },
        )
        val time = remember(report.at) {
            java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(report.at))
        }
        HealthAlert(
            hazeState = hazeState,
            title = stringResource(R.string.crash_card_title),
            description = stringResource(R.string.crash_card_body, time, reason),
            actions = listOf(
                stringResource(R.string.copy_log) to {
                    copyLog(context, "BitChord KO ${BuildConfig.VERSION_NAME} · abnormal exit\n\n${report.text}")
                    CrashRecord.dismiss()
                },
            ),
            onDismiss = { CrashRecord.dismiss() },
        )
    }
}

private fun copyLog(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("BitChord log", text))
    val count = text.lineSequence().count()
    Toast.makeText(
        context,
        context.resources.getQuantityString(R.plurals.log_copied_line_count, count, count),
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * Title, text, a few actions and Close, in a window of its own: the player
 * and its menus are windows too, and an alert in the activity's would open
 * behind them.
 */
@Composable
private fun HealthAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, frosted = false) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            actions.forEach { (label, action) ->
                AlertRule()
                AlertAction(label = label, emphasised = false, onClick = action)
            }
            AlertRule()
            AlertAction(label = stringResource(R.string.close), emphasised = true, onClick = onDismiss)
        }
    }
}
