package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.lyrics.ClaudeDiagnostics
import com.music.bitchord.ui.player.claudeFailureLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Alerts opened from the KO settings tab that have no state of their own in
 * MainActivity: set the flag and [ClaudeAlerts] shows it. Kept here so the
 * app's main composable, which is near the JVM's method size limit, does not
 * grow with each one.
 */
object KoAlerts {
    val monthlyCap = MutableStateFlow(false)

    /** The log of playlists and albums translated ahead. */
    val aheadLog = MutableStateFlow(false)

    /** Which of the floating bar's choices is open: "size", "opacity" or "show"; null for none. */
    val floatingChoice = MutableStateFlow<String?>(null)

    /** A release waiting on the "translate ahead" dialog's answer. */
    val aheadAsk = MutableStateFlow<AheadAsk?>(null)
}

/** The monthly limits offered, in dollars; 0 is no limit. */
private val MONTHLY_CAPS = listOf(0.0, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0)

/** "$1", "$0.50" — how a limit reads on its row and in the list. */
fun monthlyCapLabel(cap: Double): String =
    if (cap == Math.floor(cap)) "$" + cap.toLong() else "$" + String.format(java.util.Locale.US, "%.2f", cap)

/**
 * The Claude alerts Settings opens — API key, model, speech level, style and
 * the usage sheet — hosted here rather than inline in BitChordApp, whose
 * single composable is already at the JVM's method-size limit: every alert
 * added there costs bytecode in one method, and one more pushed it over.
 */
@Composable
fun ClaudeAlerts(
    hazeState: HazeState,
    showKey: Boolean,
    onDismissKey: () -> Unit,
    showModel: Boolean,
    onDismissModel: () -> Unit,
    showSpeech: Boolean,
    onDismissSpeech: () -> Unit,
    showMode: Boolean,
    onDismissMode: () -> Unit,
    showUsage: Boolean,
    onDismissUsage: () -> Unit,
    showLog: Boolean = false,
    onDismissLog: () -> Unit = {},
) {
    TranslateAheadAlerts(hazeState)
    FloatingLyricsAlerts(hazeState)
    val showCap by KoAlerts.monthlyCap.collectAsStateWithLifecycle()
    if (showCap) {
        BackHandler { KoAlerts.monthlyCap.value = false }
        val selected by AppSettings.claudeMonthlyCap.collectAsStateWithLifecycle()
        val noneLabel = stringResource(R.string.claude_monthly_cap_none)
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_monthly_cap),
            message = stringResource(R.string.claude_monthly_cap_description),
            options = MONTHLY_CAPS,
            selected = MONTHLY_CAPS.minByOrNull { kotlin.math.abs(it - selected) } ?: 0.0,
            label = { if (it <= 0.0) noneLabel else monthlyCapLabel(it) },
            onSelect = {
                AppSettings.setClaudeMonthlyCap(it)
                KoAlerts.monthlyCap.value = false
            },
            onDismiss = { KoAlerts.monthlyCap.value = false },
        )
    }
    if (showLog) {
        BackHandler { onDismissLog() }
        ClaudeLogAlert(hazeState = hazeState, onDismiss = onDismissLog)
    }
    if (showKey) {
        BackHandler { onDismissKey() }
        val savedKey by AppSettings.claudeApiKey.collectAsStateWithLifecycle()
        var keyInput by remember { mutableStateOf(savedKey) }
        ApiKeyAlert(
            hazeState = hazeState,
            titleRes = R.string.claude_api_key,
            descriptionRes = R.string.claude_api_key_description,
            value = keyInput,
            onValueChange = { keyInput = it },
            hasSavedKey = savedKey.isNotBlank(),
            onSave = {
                val key = keyInput.trim()
                if (key.isNotEmpty()) {
                    AppSettings.setClaudeApiKey(key)
                    AppSettings.setClaudeTranslation(true)
                    onDismissKey()
                }
            },
            onRemove = {
                AppSettings.setClaudeApiKey("")
                AppSettings.setClaudeTranslation(false)
                onDismissKey()
            },
            onDismiss = { onDismissKey() },
        )
    }

    if (showModel) {
        BackHandler { onDismissModel() }
        val selected by AppSettings.claudeModel.collectAsStateWithLifecycle()
        val options = listOf(
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_HAIKU,
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET,
        )
        // Resolved here: the alert's label lambdas are not composable.
        val labels = options.associateWith { stringResource(com.music.bitchord.ui.screens.claudeModelLabelRes(it)) }
        val details = options.associateWith { stringResource(com.music.bitchord.ui.screens.claudeModelDetailRes(it)) }
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_model),
            message = stringResource(R.string.claude_model_description),
            options = options,
            selected = selected,
            label = { labels[it].orEmpty() },
            detail = { details[it] },
            onSelect = {
                AppSettings.setClaudeModel(it)
                onDismissModel()
            },
            onDismiss = { onDismissModel() },
        )
    }

    if (showSpeech) {
        BackHandler { onDismissSpeech() }
        val selected by AppSettings.claudeSpeech.collectAsStateWithLifecycle()
        val options = listOf(
            com.music.bitchord.data.lyrics.LyricsTranslation.SPEECH_AUTO,
            com.music.bitchord.data.lyrics.LyricsTranslation.SPEECH_CASUAL,
            com.music.bitchord.data.lyrics.LyricsTranslation.SPEECH_POLITE,
        )
        val labels = options.associateWith { stringResource(com.music.bitchord.ui.screens.claudeSpeechLabelRes(it)) }
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_speech),
            message = stringResource(R.string.claude_speech_description),
            options = options,
            selected = selected,
            label = { labels[it].orEmpty() },
            onSelect = {
                AppSettings.setClaudeSpeech(it)
                onDismissSpeech()
            },
            onDismiss = { onDismissSpeech() },
        )
    }

    if (showMode) {
        BackHandler { onDismissMode() }
        val selected by AppSettings.claudeMode.collectAsStateWithLifecycle()
        val options = listOf(
            com.music.bitchord.data.lyrics.LyricsTranslation.MODE_NATURAL,
            com.music.bitchord.data.lyrics.LyricsTranslation.MODE_LITERAL,
        )
        val labels = options.associateWith { stringResource(com.music.bitchord.ui.screens.claudeModeLabelRes(it)) }
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_mode),
            message = stringResource(R.string.claude_mode_description),
            options = options,
            selected = selected,
            label = { labels[it].orEmpty() },
            onSelect = {
                AppSettings.setClaudeMode(it)
                onDismissMode()
            },
            onDismiss = { onDismissMode() },
        )
    }

    if (showUsage) {
        BackHandler { onDismissUsage() }
        val usage by AppSettings.claudeUsage.collectAsStateWithLifecycle()
        val totalLine = stringResource(
            R.string.claude_usage_subtitle,
            com.music.bitchord.ui.screens.compactTokens(usage.inputTokens),
            com.music.bitchord.ui.screens.compactTokens(usage.outputTokens),
            String.format(java.util.Locale.US, "%.2f", usage.estimatedUsd()),
        )
        val haikuLabel = stringResource(R.string.claude_model_haiku)
        val sonnetLabel = stringResource(R.string.claude_model_sonnet)
        val previousLine = usage.previousMonthUsd?.let {
            stringResource(R.string.claude_usage_previous, String.format(java.util.Locale.US, "%.2f", it))
        }
        val noteLine = stringResource(R.string.claude_usage_note)
        val lines = buildList {
            add(totalLine)
            usage.inputByModel.keys.union(usage.outputByModel.keys).sorted().forEach { model ->
                // Tokens that went through a batch are tallied apart: they cost half.
                val batch = model.endsWith(com.music.bitchord.data.lyrics.LyricsTranslation.BATCH_MODEL_SUFFIX)
                val plain = model.removeSuffix(com.music.bitchord.data.lyrics.LyricsTranslation.BATCH_MODEL_SUFFIX)
                val label = (if (plain == com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET) sonnetLabel else haikuLabel) +
                    if (batch) " (Batch)" else ""
                add(
                    "$label: ${com.music.bitchord.ui.screens.compactTokens(usage.inputByModel[model] ?: 0L)} / " +
                        com.music.bitchord.ui.screens.compactTokens(usage.outputByModel[model] ?: 0L),
                )
            }
            previousLine?.let { add(it) }
            add(noteLine)
        }
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_usage),
            description = lines.joinToString("\n"),
            confirmLabel = stringResource(R.string.claude_usage_reset),
            onConfirm = {
                AppSettings.resetClaudeUsage()
                onDismissUsage()
            },
            onDismiss = { onDismissUsage() },
        )
    }
}

/** The floating bar's text size, background and contents, opened from its rows in Settings. */
@Composable
private fun FloatingLyricsAlerts(hazeState: HazeState) {
    val choice by KoAlerts.floatingChoice.collectAsStateWithLifecycle()
    val close = { KoAlerts.floatingChoice.value = null }
    if (choice != null) BackHandler { close() }
    when (choice) {
        "size" -> {
            val selected by AppSettings.floatingLyricsSize.collectAsStateWithLifecycle()
            val options = listOf(AppSettings.FLOATING_SMALL, AppSettings.FLOATING_MEDIUM, AppSettings.FLOATING_LARGE)
            val labels = options.associateWith { stringResource(floatingSizeLabelRes(it)) }
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.floating_lyrics_size),
                message = null,
                options = options,
                selected = selected,
                label = { labels[it].orEmpty() },
                onSelect = {
                    AppSettings.setFloatingLyricsSize(it)
                    close()
                },
                onDismiss = close,
            )
        }
        "opacity" -> {
            val selected by AppSettings.floatingLyricsOpacity.collectAsStateWithLifecycle()
            val options = listOf(40, 60, 85, 100)
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.floating_lyrics_opacity),
                message = stringResource(R.string.floating_lyrics_opacity_description),
                options = options,
                selected = options.minByOrNull { kotlin.math.abs(it - selected) } ?: 85,
                label = { "$it%" },
                onSelect = {
                    AppSettings.setFloatingLyricsOpacity(it)
                    close()
                },
                onDismiss = close,
            )
        }
        "show" -> {
            val selected by AppSettings.floatingLyricsShow.collectAsStateWithLifecycle()
            val options = listOf(AppSettings.FLOATING_BOTH, AppSettings.FLOATING_ORIGINAL, AppSettings.FLOATING_TRANSLATION)
            val labels = options.associateWith { stringResource(floatingShowLabelRes(it)) }
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.floating_lyrics_show),
                message = stringResource(R.string.floating_lyrics_show_description),
                options = options,
                selected = selected,
                label = { labels[it].orEmpty() },
                onSelect = {
                    AppSettings.setFloatingLyricsShow(it)
                    close()
                },
                onDismiss = close,
            )
        }
    }
}

fun floatingSizeLabelRes(size: String): Int = when (size) {
    AppSettings.FLOATING_SMALL -> R.string.floating_lyrics_size_small
    AppSettings.FLOATING_LARGE -> R.string.floating_lyrics_size_large
    else -> R.string.floating_lyrics_size_medium
}

fun floatingShowLabelRes(show: String): Int = when (show) {
    AppSettings.FLOATING_ORIGINAL -> R.string.floating_lyrics_show_original
    AppSettings.FLOATING_TRANSLATION -> R.string.floating_lyrics_show_translation
    else -> R.string.floating_lyrics_show_both
}

/**
 * The last Claude requests and how each went, newest first — what was asked,
 * of which model, and why it failed when it did. Copy puts the whole log on
 * the clipboard to paste into a bug report; nothing in it is secret (no key,
 * no lyrics).
 */
@Composable
private fun ClaudeLogAlert(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by ClaudeDiagnostics.entries.collectAsStateWithLifecycle()
    val okText = stringResource(R.string.claude_log_ok)
    val copiedText = stringResource(R.string.claude_log_copied)
    val purposeTranslate = stringResource(R.string.claude_purpose_translate)
    val purposeMeaning = stringResource(R.string.claude_purpose_meaning)
    val purposeName = stringResource(R.string.claude_purpose_original_name)
    val purposeReading = stringResource(R.string.claude_purpose_reading)
    val purposeBatch = stringResource(R.string.claude_purpose_batch)
    val haikuLabel = stringResource(R.string.claude_model_haiku)
    val sonnetLabel = stringResource(R.string.claude_model_sonnet)
    // Every label the lines can need, resolved up front: the loop below is not
    // where composable calls belong, and a failure kind is one of a few.
    val failureLabels = com.music.bitchord.data.lyrics.ClaudeFailure.entries.associateWith { claudeFailureLabel(it) }
    val time = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }
    val lines = entries.map { entry ->
        val purpose = when (entry.purpose) {
            ClaudeDiagnostics.PURPOSE_TRANSLATE -> purposeTranslate
            ClaudeDiagnostics.PURPOSE_MEANING -> purposeMeaning
            ClaudeDiagnostics.PURPOSE_ORIGINAL_NAME -> purposeName
            ClaudeDiagnostics.PURPOSE_READING -> purposeReading
            ClaudeDiagnostics.PURPOSE_BATCH -> purposeBatch
            else -> entry.purpose
        }
        val model = when (entry.model) {
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET -> sonnetLabel
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_HAIKU -> haikuLabel
            else -> entry.model
        }
        val outcome = entry.failureKind?.let { "✗ ${failureLabels[it].orEmpty()}" } ?: "✓ $okText"
        buildString {
            append(time.format(Date(entry.at))).append(" · ").append(purpose).append(" · ").append(model)
            append('\n').append(outcome)
            if (entry.linesAsked != null) {
                append(" · ").append(entry.linesGot?.toString() ?: "-").append('/').append(entry.linesAsked)
            }
            if (entry.elapsedMs > 0) append(" · ").append(String.format(Locale.US, "%.1fs", entry.elapsedMs / 1000.0))
            entry.httpCode?.let { append(" · HTTP ").append(it) }
            entry.stopReason?.let { append(" · ").append(it) }
            if (entry.inputTokens != null || entry.outputTokens != null) {
                append(" · in ").append(entry.inputTokens ?: 0).append("/out ").append(entry.outputTokens ?: 0)
            }
            entry.detail?.let { append('\n').append("  ").append(it) }
        }
    }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.claude_log),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (entries.isEmpty()) {
                    stringResource(R.string.claude_log_empty)
                } else {
                    stringResource(R.string.claude_log_subtitle, entries.size, entries.count { it.failed })
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (lines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            modifier = Modifier.padding(vertical = 5.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (lines.isNotEmpty()) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.claude_log_copy),
                emphasised = true,
                onClick = {
                    val header = "BitChord KO ${com.music.bitchord.BuildConfig.VERSION_NAME} · Claude log (${entries.size})"
                    val text = (listOf(header) + lines).joinToString("\n\n")
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Claude log", text))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                },
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.claude_log_clear),
                emphasised = false,
                destructive = true,
                onClick = { scope.launch { ClaudeDiagnostics.clear() } },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.close), emphasised = false, onClick = onDismiss)
    }
}
