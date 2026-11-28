package com.music.bitchord.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState

/**
 * KO: what this build brought, shown once after updating to it — the same
 * release notes the update window showed, a page per section — with "don't
 * show again". A fresh install is not an update and shows nothing.
 */
object WhatsNew {

    /** The notes to show for this build, or null: shown already, switched off, a fresh install, or unreachable. */
    suspend fun pending(context: Context): String? {
        val version = BuildConfig.VERSION_NAME
        if (AppSettings.whatsNewNever.value || AppSettings.whatsNewSeen.value == version) return null
        if (AppSettings.whatsNewSeen.value.isEmpty() && freshInstall(context)) {
            AppSettings.setWhatsNewSeen(version)
            return null
        }
        // Every release since the one last shown here, so a skipped beta is not lost.
        // Unreachable now: tried again on the next launch.
        val includeBeta = AppSettings.betaUpdates.value || version.endsWith("-beta")
        return AppUpdateChecker.notesSince(AppSettings.whatsNewSeen.value, version, includeBeta)
    }

    private fun freshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)
}

/** Hosted from [KoHealthAlerts]: once onboarding is done, the window if this build has one to show. */
@Composable
internal fun WhatsNewAlerts(hazeState: HazeState) {
    val context = LocalContext.current
    val onboarded by AppSettings.onboardingComplete.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(onboarded) {
        if (onboarded) notes = WhatsNew.pending(context)
    }
    val shown = notes ?: return
    var never by remember { mutableStateOf(false) }
    val close = {
        AppSettings.setWhatsNewSeen(BuildConfig.VERSION_NAME)
        if (never) AppSettings.setWhatsNewNever(true)
        notes = null
    }
    // KO: the whole screen, not an alert: a release's notes run long, and an alert showed a few lines.
    UpdateNewsWindow(
        title = stringResource(R.string.whats_new_title),
        subtitle = stringResource(R.string.whats_new_updated_to, BuildConfig.VERSION_NAME),
        notes = shown,
        onClose = close,
    ) {
        AheadOptionRow(
            label = stringResource(R.string.whats_new_never),
            detail = stringResource(R.string.whats_new_never_detail),
            checked = never,
            checkbox = true,
            onClick = { never = !never },
        )
        AlertAction(label = stringResource(R.string.whats_new_ok), emphasised = true, onClick = close)
    }
}

/** Settings' "what's new after an update" switch: on unless "don't show again" was ticked. */
@Composable
fun WhatsNewRow(title: String) {
    val never by AppSettings.whatsNewNever.collectAsStateWithLifecycle()
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Upgrade,
        title = title,
        subtitle = stringResource(R.string.whats_new_row_subtitle),
        trailing = { IosSwitch(checked = !never, onCheckedChange = { on -> AppSettings.setWhatsNewNever(!on) }) },
        onClick = { AppSettings.setWhatsNewNever(!never) },
    )
}

/** KO: Settings › 기타 — the lyrics discs' first-use hints shown once more; greyed out while none has been seen. */
@Composable
fun LyricsHintsResetRow(title: String) {
    val seen by AppSettings.lyricsHintsSeen.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Info,
        title = title,
        subtitle = stringResource(R.string.lyrics_hints_reset_subtitle),
        enabled = seen.isNotEmpty(),
        onClick = {
            AppSettings.resetLyricsHints()
            android.widget.Toast.makeText(context, R.string.lyrics_hints_reset_done, android.widget.Toast.LENGTH_SHORT).show()
        },
    )
}
