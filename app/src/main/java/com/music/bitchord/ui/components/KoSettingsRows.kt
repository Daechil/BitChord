package com.music.bitchord.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/*
 * The second lines of a few KO rows in Settings, each reading what it shows
 * itself: kept out of SettingsScreen, which is already one very large function.
 */

@Composable
private fun RowSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 5,
    )
}

/** "$1 (약 1,400원) · 이번 달 $0.42 (약 590원) 사용", and how much of the limit that is. */
@Composable
fun MonthlyCapSubtitle(cap: Double, usage: AppSettings.ClaudeUsage) {
    val format = koFormat()
    // The jobs still out count toward the limit: read again as they change.
    val jobs by com.music.bitchord.download.TranslateAhead.jobs.collectAsStateWithLifecycle()
    val first = stringResource(
        R.string.claude_monthly_cap_subtitle,
        if (cap <= 0.0) stringResource(R.string.claude_monthly_cap_none) else format.limit(cap),
        format.money(AppSettings.claudeSpentThisMonth()),
    )
    val share = remember(cap, usage, jobs) {
        if (cap > 0.0) com.music.bitchord.download.ClaudeBudget.monthShare() else null
    }
    val second = share?.let { stringResource(R.string.claude_monthly_cap_share, (it * 100).toInt()) }
    RowSubtitle(listOfNotNull(first, second).joinToString("\n"))
}

/** "달러와 원화 · 1달러 = 1,400원 · 경고 80% · 하루 없음". */
@Composable
fun CostSettingsSubtitle() {
    val display by AppSettings.moneyDisplay.collectAsStateWithLifecycle()
    val rate by AppSettings.krwPerUsd.collectAsStateWithLifecycle()
    val warnAt by AppSettings.claudeCapWarnPercent.collectAsStateWithLifecycle()
    val monthlyCap by AppSettings.claudeMonthlyCap.collectAsStateWithLifecycle()
    val dailyCap by AppSettings.claudeDailyCap.collectAsStateWithLifecycle()
    val format = koFormat()
    val shown = stringResource(
        when (display) {
            AppSettings.MONEY_USD -> R.string.money_display_usd
            AppSettings.MONEY_KRW -> R.string.money_display_krw
            else -> R.string.money_display_both
        },
    ) + if (display == AppSettings.MONEY_USD) {
        ""
    } else {
        " · " + stringResource(R.string.krw_rate_value, java.text.NumberFormat.getIntegerInstance().format(rate))
    }
    val warning = if (monthlyCap <= 0.0 || warnAt <= 0) stringResource(R.string.cap_warn_off) else "$warnAt%"
    val daily = if (dailyCap <= 0.0) stringResource(R.string.daily_cap_none) else format.limit(dailyCap)
    RowSubtitle(stringResource(R.string.claude_cost_settings_subtitle, shown, warning, daily))
}

/** "한 번에 20곡씩 보내요". */
@Composable
fun ChunkSizeSubtitle() {
    val songs by AppSettings.translateAheadChunk.collectAsStateWithLifecycle()
    RowSubtitle(stringResource(R.string.translate_ahead_chunk_subtitle, songs))
}
