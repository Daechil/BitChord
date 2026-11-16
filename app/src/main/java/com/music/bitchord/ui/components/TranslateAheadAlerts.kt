package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.download.TranslateAhead
import com.music.bitchord.ui.player.claudeFailureLabel
import com.music.bitchord.ui.screens.claudeModeLabelRes
import com.music.bitchord.ui.screens.claudeSpeechLabelRes
import com.music.bitchord.ui.screens.compactTokens
import dev.chrisbanes.haze.HazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The alerts of [TranslateAhead], shown by the flags in [KoAlerts]: hosted by
 * [ClaudeAlerts], so the app's main composable holds no state for them.
 */
@Composable
fun TranslateAheadAlerts(hazeState: HazeState) {
    val showLog by KoAlerts.aheadLog.collectAsStateWithLifecycle()
    if (showLog) {
        var opened by remember { mutableStateOf<String?>(null) }
        val jobs by TranslateAhead.jobs.collectAsStateWithLifecycle()
        val job = opened?.let { id -> jobs.firstOrNull { it.id == id } }
        BackHandler {
            if (job != null) opened = null else KoAlerts.aheadLog.value = false
        }
        if (job != null) {
            TranslateAheadJobAlert(hazeState = hazeState, job = job, onBack = { opened = null })
        } else {
            TranslateAheadLogAlert(
                hazeState = hazeState,
                jobs = jobs,
                onOpen = { opened = it },
                onDismiss = { KoAlerts.aheadLog.value = false },
            )
        }
    }
}

/** "Finding lyrics 12/40", "Waiting for Claude", "Done" — where a job stands. */
@Composable
private fun stateLabel(job: TranslateAhead.Job): String = when (job.state) {
    TranslateAhead.State.PREPARING -> stringResource(R.string.translate_ahead_state_preparing, job.prepared, job.tracks.size)
    TranslateAhead.State.WAITING -> stringResource(R.string.translate_ahead_state_waiting)
    TranslateAhead.State.CANCELING -> stringResource(R.string.translate_ahead_state_canceling)
    TranslateAhead.State.DONE -> stringResource(R.string.translate_ahead_state_done)
    TranslateAhead.State.EXPIRED -> stringResource(R.string.translate_ahead_state_expired)
    TranslateAhead.State.CANCELED -> stringResource(R.string.translate_ahead_state_canceled)
    else -> stringResource(R.string.translate_ahead_state_failed)
}

@Composable
private fun modelLabel(model: String): String = stringResource(
    if (model == LyricsTranslation.CLAUDE_SONNET) R.string.claude_model_sonnet else R.string.claude_model_haiku,
)

private fun usd(value: Double): String = String.format(Locale.US, if (value < 0.1) "%.3f" else "%.2f", value)

/** Every job, newest first: what was asked for, when, and how far it has got. */
@Composable
private fun TranslateAheadLogAlert(
    hazeState: HazeState,
    jobs: List<TranslateAhead.Job>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        AlertHeading(
            title = stringResource(R.string.translate_ahead_log),
            message = stringResource(
                if (jobs.isEmpty()) R.string.translate_ahead_log_empty else R.string.translate_ahead_log_hint,
            ),
        )
        if (jobs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                jobs.forEach { job ->
                    AlertRule()
                    val translated = job.count(TranslateAhead.TrackStatus.TRANSLATED)
                    val model = modelLabel(job.model)
                    val state = stateLabel(job)
                    val line = buildString {
                        append(time.format(Date(job.createdAt))).append(" · ").append(model)
                        append(" · ").append(state)
                        if (!job.active) {
                            append(" · ").append(translated).append('/').append(job.tracks.size)
                            if (job.inputTokens > 0 || job.outputTokens > 0) append(" · $").append(usd(job.costUsd()))
                        }
                    }
                    TappableLines(title = job.title, detail = line, onClick = { onOpen(job.id) })
                }
            }
        }
        if (jobs.any { !it.active }) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translate_ahead_clear),
                emphasised = false,
                destructive = true,
                onClick = { TranslateAhead.clearFinished() },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.close), emphasised = false, onClick = onDismiss)
    }
}

/** One job: its totals, then every song and what became of it. */
@Composable
private fun TranslateAheadJobAlert(hazeState: HazeState, job: TranslateAhead.Job, onBack: () -> Unit) {
    val status = TranslateAhead.TrackStatus
    val statusLabels = mapOf(
        status.PENDING to stringResource(R.string.translate_ahead_track_pending),
        status.SENT to stringResource(R.string.translate_ahead_track_sent),
        status.TRANSLATED to stringResource(R.string.translate_ahead_track_translated),
        status.ALREADY to stringResource(R.string.translate_ahead_track_already),
        status.NO_LYRICS to stringResource(R.string.translate_ahead_track_no_lyrics),
        status.SAME_LANGUAGE to stringResource(R.string.translate_ahead_track_same_language),
        status.DECLINED to stringResource(R.string.translate_ahead_track_declined),
        status.FAILED to stringResource(R.string.translate_ahead_track_failed),
    )
    // Resolved up front: the track loop below is not where composable calls belong.
    val failureLabels = ClaudeFailure.entries.associate { it.name to claudeFailureLabel(it) }
    val changedLabel = stringResource(R.string.translate_ahead_track_changed)
    val readingLabel = stringResource(R.string.translate_ahead_reading_label)
    val language = remember(job.target) {
        Locale.forLanguageTag(job.target.replace('_', '-')).getDisplayName(Locale.getDefault()).ifBlank { job.target }
    }
    val style = listOf(
        language,
        stringResource(claudeSpeechLabelRes(job.speech)),
        stringResource(claudeModeLabelRes(job.mode)),
    ).joinToString(" · ")
    val headLine = modelLabel(job.model) + " · " + stateLabel(job) +
        (job.error?.let { " (" + (failureLabels[it] ?: it) + ")" } ?: "")
    val countsLine = stringResource(
        R.string.translate_ahead_summary_counts,
        job.count(status.TRANSLATED),
        job.count(status.ALREADY),
        job.count(status.NO_LYRICS),
        job.count(status.DECLINED),
        job.count(status.FAILED),
    )
    val costLine = stringResource(
        R.string.translate_ahead_summary_cost,
        compactTokens(job.inputTokens),
        compactTokens(job.outputTokens),
        usd(job.costUsd()),
    )
    val expiredLine = stringResource(R.string.translate_ahead_expired_note)
    val summary = listOfNotNull(
        headLine,
        style,
        countsLine,
        costLine.takeIf { job.inputTokens > 0 || job.outputTokens > 0 },
        expiredLine.takeIf { job.state == TranslateAhead.State.EXPIRED },
    )
    val lines = job.tracks.map { track ->
        val mark = when (track.status) {
            status.TRANSLATED, status.ALREADY -> "✓"
            status.DECLINED, status.FAILED -> "✗"
            status.PENDING, status.SENT -> "…"
            else -> "–"
        }
        buildString {
            append(mark).append(' ').append(track.title)
            if (track.artist.isNotBlank()) append(" — ").append(track.artist)
            append('\n').append("   ").append(statusLabels[track.status] ?: track.status)
            if (track.status == status.FAILED) track.detail?.let { append(" (").append(failureLabels[it] ?: it).append(')') }
            track.readingStatus?.let { reading ->
                append(" · ").append(readingLabel.format(statusLabels[reading] ?: reading))
            }
            if (track.lyricsChanged) append('\n').append("   ").append(changedLabel)
        }
    }
    val anyDeclined = job.tracks.any { it.status == status.DECLINED || it.readingStatus == status.DECLINED }
    val anyFailed = job.tracks.any { it.status == status.FAILED || it.readingStatus == status.FAILED }
    AlertScaffold(hazeState = hazeState, onDismiss = onBack) {
        AlertHeading(title = job.title, message = summary.joinToString("\n"))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (job.state == TranslateAhead.State.PREPARING || job.state == TranslateAhead.State.WAITING) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translate_ahead_cancel),
                emphasised = false,
                destructive = true,
                onClick = { TranslateAhead.cancel(job.id) },
            )
        }
        if (!job.active && anyDeclined && job.model != LyricsTranslation.CLAUDE_SONNET) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translate_ahead_resend_sonnet),
                emphasised = true,
                onClick = {
                    TranslateAhead.resend(job.id, declined = true)
                    onBack()
                },
            )
        }
        if (!job.active && anyFailed) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translate_ahead_resend_failed),
                emphasised = false,
                onClick = {
                    TranslateAhead.resend(job.id, declined = false)
                    onBack()
                },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.back), emphasised = false, onClick = onBack)
    }
}

@Composable
internal fun AlertHeading(title: String, message: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A row of an alert that opens something: a title, and a quieter line under it. */
@Composable
internal fun TappableLines(title: String, detail: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}
