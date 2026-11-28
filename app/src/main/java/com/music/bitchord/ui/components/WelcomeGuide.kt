package com.music.bitchord.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * KO: the first-use guide — the features of this build that are easy to miss, a
 * page a theme, each with what it does and where it is. Shown once to a new
 * install after upstream's onboarding (name, sign-in); never on its own to someone
 * updating, who finds it in Settings › 기타 like everyone else.
 *
 * Every place named in it is a real menu path, written from the labels the
 * screens show; a guide that sends people to a row that is not there is worse than
 * none. There are no "take me there" buttons: nothing outside Settings can open a
 * Settings page today.
 */
internal object WelcomeGuide {
    const val UNDECIDED = 0
    const val PENDING = 1
    const val DONE = 2

    /** Open from Settings › 기타, or by [WelcomeGuideHost] for a new install. */
    val open = MutableStateFlow(false)

    /**
     * Decided once, on the first launch of a build that has the guide: someone not
     * through onboarding yet, or on a fresh install, is new and gets it; anyone else
     * has been using the app and does not. (Install time alone would miss a person
     * who installed, stopped at onboarding, and updated before finishing it.)
     */
    fun firstState(onboarded: Boolean, freshInstall: Boolean): Int =
        if (!onboarded || freshInstall) PENDING else DONE

    /** Whether to open it now: still pending, and onboarding (name, sign-in) out of the way. */
    fun shouldShow(state: Int, onboarded: Boolean): Boolean = state == PENDING && onboarded

    class Row(@StringRes val name: Int, @StringRes val body: Int, @StringRes val where: Int? = null)
    class Page(val mark: String, @StringRes val title: Int, @StringRes val intro: Int? = null, val rows: List<Row>)

    val pages: List<Page> = listOf(
        Page("👋", R.string.guide_p1_title, R.string.guide_p1_intro, emptyList()),
        Page(
            "🎤", R.string.guide_p2_title, null,
            listOf(
                Row(R.string.guide_lyrics_translate_name, R.string.guide_lyrics_translate_body, R.string.guide_where_player_lyrics),
                Row(R.string.guide_lyrics_retranslate_name, R.string.guide_lyrics_retranslate_body),
                Row(R.string.guide_lyrics_furigana_name, R.string.guide_lyrics_furigana_body, R.string.guide_where_player_furigana),
                Row(R.string.prioritize_syllable_lyrics, R.string.guide_lyrics_syllable_body, R.string.guide_where_lyrics_sources),
            ),
        ),
        Page(
            "✨", R.string.guide_p3_title, R.string.guide_p3_intro,
            listOf(
                Row(R.string.claude_translation, R.string.guide_claude_on_body, R.string.guide_where_ko_claude),
                Row(R.string.auto_claude_translation, R.string.guide_claude_auto_body, R.string.guide_where_ko_claude),
                Row(R.string.translate_ahead, R.string.guide_claude_ahead_body, R.string.guide_where_ahead_menu),
                Row(R.string.claude_monthly_cap, R.string.guide_claude_cap_body, R.string.guide_where_ko_cost),
            ),
        ),
        Page(
            "🎧", R.string.guide_p4_title, null,
            listOf(
                Row(R.string.floating_lyrics, R.string.guide_listen_floating_body, R.string.guide_where_ko_playback),
                Row(R.string.bluetooth_lyrics, R.string.guide_listen_bluetooth_body, R.string.guide_where_ko_playback),
                Row(R.string.radio_fresh, R.string.guide_listen_fresh_body, R.string.guide_where_ko_queue),
                Row(R.string.queue_tidy_auto, R.string.guide_listen_tidy_body, R.string.guide_where_ko_queue),
            ),
        ),
        Page(
            "🎵", R.string.guide_p5_title, null,
            listOf(
                Row(R.string.guide_spotify_play_name, R.string.guide_spotify_play_body, R.string.guide_where_spotify),
                Row(R.string.guide_spotify_pick_name, R.string.guide_spotify_pick_body),
                Row(R.string.guide_spotify_save_name, R.string.guide_spotify_save_body, R.string.guide_where_spotify_save),
            ),
        ),
        Page(
            "👥", R.string.listen_together, null,
            listOf(
                Row(R.string.listen_together, R.string.guide_party_body, R.string.guide_where_party),
                Row(R.string.guide_party_link_name, R.string.guide_party_link_body),
            ),
        ),
        Page(
            "📌", R.string.guide_p7_title, null,
            listOf(
                Row(R.string.guide_more_chips_name, R.string.guide_more_chips_body, R.string.guide_where_ko),
                Row(R.string.whats_new_row, R.string.guide_more_whats_new_body, R.string.guide_where_whats_new),
                Row(R.string.guide_replay_row, R.string.guide_more_replay_body, R.string.guide_where_replay),
            ),
        ),
    )
}

/** Hosted from [KoHealthAlerts]: decides the guide once, and opens it for a new install after onboarding. */
@Composable
internal fun WelcomeGuideHost() {
    val context = LocalContext.current
    val onboarded by AppSettings.onboardingComplete.collectAsStateWithLifecycle()
    val state by AppSettings.welcomeGuideState.collectAsStateWithLifecycle()
    LaunchedEffect(onboarded, state) {
        when {
            state == WelcomeGuide.UNDECIDED ->
                AppSettings.setWelcomeGuideState(WelcomeGuide.firstState(onboarded, freshInstall(context)))
            WelcomeGuide.shouldShow(state, onboarded) -> WelcomeGuide.open.value = true
        }
    }
    val open by WelcomeGuide.open.collectAsState()
    if (!open) return
    val replayHint = stringResource(R.string.guide_skipped_notice)
    WelcomeGuideWindow(
        onFinish = { skipped ->
            WelcomeGuide.open.value = false
            AppSettings.setWelcomeGuideState(WelcomeGuide.DONE)
            // Said on the way out too, for whoever skipped before the last page said it.
            if (skipped) KoNotice.show(replayHint)
        },
    )
}

private fun freshInstall(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.firstInstallTime == info.lastUpdateTime
}.getOrDefault(false)

@Composable
private fun WelcomeGuideWindow(onFinish: (skipped: Boolean) -> Unit) {
    val pages = WelcomeGuide.pages
    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val last = index == pages.lastIndex
    val listState = rememberLazyListState()
    LaunchedEffect(index) { listState.scrollToItem(0) }
    val title = stringResource(page.title)
    val intro = page.intro?.let { stringResource(it) }
    val whereLabel = stringResource(R.string.guide_where)
    KoWindow(
        title = stringResource(R.string.guide_title),
        // Back is "skip": the guide is never in the way of the app.
        onBack = { onFinish(!last) },
        listState = listState,
        overlay = {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GuideTextButton(stringResource(R.string.guide_back), enabled = index > 0) { index-- }
                    Text(
                        text = stringResource(R.string.guide_page, index + 1, pages.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GuideTextButton(stringResource(R.string.guide_skip), enabled = !last) { onFinish(true) }
                }
                AlertAction(
                    label = stringResource(if (last) R.string.guide_done else R.string.guide_next),
                    emphasised = true,
                    onClick = { if (last) onFinish(false) else index++ },
                )
            }
        },
    ) {
        item(contentType = "page-title") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(text = page.mark, style = MaterialTheme.typography.displaySmall)
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.W700),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        intro?.let { windowText(it) }
        if (page.rows.isNotEmpty()) {
            windowCard(rows = page.rows) { row -> GuideRowView(row, whereLabel) }
        }
        // Room for the buttons pinned under the list.
        item(contentType = "bottom") { Spacer(Modifier.height(150.dp)) }
    }
}

@Composable
private fun GuideRowView(row: WelcomeGuide.Row, whereLabel: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(row.name),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(row.body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        row.where?.let { where ->
            Text(
                text = "$whereLabel ${stringResource(where)}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun GuideTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0f),
        modifier = Modifier
            .heightIn(min = 48.dp)
            // Kept in place but silent when it does nothing, so TalkBack does not offer it.
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.clearAndSetSemantics {})
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

/** Settings › 기타: the guide once more. */
@Composable
fun WelcomeGuideReplayRow(title: String) {
    com.music.bitchord.ui.screens.SettingsRow(
        icon = com.music.bitchord.ui.icons.SfIcons.Info,
        title = title,
        subtitle = stringResource(R.string.guide_replay_row_subtitle),
        onClick = { WelcomeGuide.open.value = true },
    )
}
