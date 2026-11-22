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

    /** The update Settings' "Check for updates" found, opened over Settings. */
    val openUpdate = MutableStateFlow(false)

    /** The log of playlists and albums translated ahead. */
    val aheadLog = MutableStateFlow(false)

    /** Which of the floating bar's choices is open: "size", "opacity" or "show"; null for none. */
    val floatingChoice = MutableStateFlow<String?>(null)

    /** A song held back from a playlist because another release of it is already there. */
    val similarSong = MutableStateFlow<SimilarAsk?>(null)

    /** A playlist being looked through for songs it holds twice. */
    val duplicates = MutableStateFlow<DuplicateAsk?>(null)

    /** A song whose stored translations are on show. */
    val songStatus = MutableStateFlow<com.music.bitchord.data.model.Song?>(null)

    /** A playlist or album whose songs' translation status is on show. */
    val releaseStatus = MutableStateFlow<AheadAsk?>(null)

    /** The watched playlists' check interval choice. */
    val watchInterval = MutableStateFlow(false)

    /** Settings' translation storage page. */
    val storage = MutableStateFlow(false)

    /** A release waiting on the "translate ahead" dialog's answer. */
    val aheadAsk = MutableStateFlow<AheadAsk?>(null)

    /** Settings' window of amounts and limits: how money is shown, the rate, the limits and the warning. */
    val costSettings = MutableStateFlow(false)

    /** The translate-ahead batch size choice. */
    val chunkSize = MutableStateFlow(false)

    /** The floating bar's window of looks, timing and position. */
    val floatingSettings = MutableStateFlow(false)

    /** The window of the romanize button's reading and the listener's own instructions for Claude. */
    val lyricsAdvanced = MutableStateFlow(false)
}

/** The batch sizes offered, in songs. */
internal val CHUNK_SIZES = listOf(10, 20, 30)

/** The daily limits offered, in dollars; 0 is no limit. */
private val DAILY_CAPS = listOf(0.0, 0.1, 0.2, 0.5, 1.0, 2.0)

/** The warning thresholds offered, in percent of the monthly limit; 0 is none. */
private val CAP_WARNINGS = listOf(0, 50, 80, 90)

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
    KoHealthAlerts(hazeState)
    TranslationStatusAlerts(hazeState)
    DuplicatesAlerts()
    SimilarSongAlert(hazeState)
    FloatingLyricsAlerts(hazeState)
    val showWatchInterval by KoAlerts.watchInterval.collectAsStateWithLifecycle()
    if (showWatchInterval) {
        BackHandler { KoAlerts.watchInterval.value = false }
        val context = LocalContext.current
        val selected by AppSettings.aheadWatchHours.collectAsStateWithLifecycle()
        val options = listOf(6, 12, 24, 48, 72)
        val hoursLabel = stringResource(R.string.translate_ahead_watch_hours)
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.translate_ahead_watch_interval),
            message = stringResource(R.string.translate_ahead_watch_interval_description),
            options = options,
            selected = options.minByOrNull { kotlin.math.abs(it - selected) } ?: 24,
            label = { hoursLabel.format(it) },
            onSelect = {
                AppSettings.setAheadWatchHours(it)
                com.music.bitchord.download.TranslateAhead.scheduleWatching(context.applicationContext, replace = true)
                KoAlerts.watchInterval.value = false
            },
            onDismiss = { KoAlerts.watchInterval.value = false },
        )
    }
    val showChunk by KoAlerts.chunkSize.collectAsStateWithLifecycle()
    if (showChunk) {
        BackHandler { KoAlerts.chunkSize.value = false }
        val selected by AppSettings.translateAheadChunk.collectAsStateWithLifecycle()
        val songsLabel = stringResource(R.string.translate_ahead_chunk_option)
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.translate_ahead_chunk),
            message = stringResource(R.string.translate_ahead_chunk_description),
            options = CHUNK_SIZES,
            selected = CHUNK_SIZES.minByOrNull { kotlin.math.abs(it - selected) } ?: 20,
            label = { songsLabel.format(it) },
            onSelect = {
                AppSettings.setTranslateAheadChunk(it)
                KoAlerts.chunkSize.value = false
            },
            onDismiss = { KoAlerts.chunkSize.value = false },
        )
    }
    val showLyricsAdvanced by KoAlerts.lyricsAdvanced.collectAsStateWithLifecycle()
    if (showLyricsAdvanced) {
        LyricsAdvancedWindow(onDismiss = { KoAlerts.lyricsAdvanced.value = false })
    }
    val showFloating by KoAlerts.floatingSettings.collectAsStateWithLifecycle()
    if (showFloating) {
        FloatingLyricsWindow(onDismiss = { KoAlerts.floatingSettings.value = false })
    }
    val showCost by KoAlerts.costSettings.collectAsStateWithLifecycle()
    if (showCost) {
        ClaudeCostWindow(onDismiss = { KoAlerts.costSettings.value = false })
    }
    val showCap by KoAlerts.monthlyCap.collectAsStateWithLifecycle()
    if (showCap) {
        BackHandler { KoAlerts.monthlyCap.value = false }
        val selected by AppSettings.claudeMonthlyCap.collectAsStateWithLifecycle()
        val noneLabel = stringResource(R.string.claude_monthly_cap_none)
        val format = koFormat()
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.claude_monthly_cap),
            message = stringResource(R.string.claude_monthly_cap_description),
            options = MONTHLY_CAPS,
            selected = MONTHLY_CAPS.minByOrNull { kotlin.math.abs(it - selected) } ?: 0.0,
            label = { if (it <= 0.0) noneLabel else format.limit(it) },
            onSelect = {
                AppSettings.setClaudeMonthlyCap(it)
                KoAlerts.monthlyCap.value = false
            },
            onDismiss = { KoAlerts.monthlyCap.value = false },
        )
    }
    if (showLog) {
        ClaudeLogWindow(onDismiss = onDismissLog)
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
        ClaudeUsageWindow(hazeState = hazeState, onDismiss = onDismissUsage)
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
 * This month's Claude use, by model, and the months before it, in a window
 * of its own: the history no longer has to fit an alert.
 */
@Composable
private fun ClaudeUsageWindow(hazeState: HazeState, onDismiss: () -> Unit) {
    val usage by AppSettings.claudeUsage.collectAsStateWithLifecycle()
    val history by AppSettings.claudeUsageHistory.collectAsStateWithLifecycle()
    val today by AppSettings.claudeToday.collectAsStateWithLifecycle()
    val dailyCap by AppSettings.claudeDailyCap.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }
    val format = koFormat()
    val totalLine = stringResource(
        R.string.claude_usage_subtitle,
        format.tokens(usage.inputTokens),
        format.tokens(usage.outputTokens),
        format.money(usage.estimatedUsd()),
    )
    val spentToday = if (today.day == java.time.LocalDate.now().toString()) today.usd else 0.0
    val todayLine = if (dailyCap > 0.0) {
        stringResource(R.string.claude_usage_today_cap, format.money(spentToday), format.limit(dailyCap))
    } else {
        stringResource(R.string.claude_usage_today, format.money(spentToday))
    }
    val haikuLabel = stringResource(R.string.claude_model_haiku)
    val sonnetLabel = stringResource(R.string.claude_model_sonnet)
    val webSearchLabel = stringResource(R.string.song_background_usage_label)
    val webSearchCount = stringResource(R.string.song_background_usage_count)
    val noteLine = stringResource(R.string.claude_usage_note)
    val monthsTitle = stringResource(R.string.claude_usage_months)
    val monthDetail = stringResource(R.string.claude_usage_month_detail)
    val byModelTitle = stringResource(R.string.ko_window_by_model)
    val suffix = com.music.bitchord.data.lyrics.LyricsTranslation.BATCH_MODEL_SUFFIX
    val models = usage.inputByModel.keys.union(usage.outputByModel.keys).sorted().map { model ->
        // Tokens that went through a batch are tallied apart: they cost half.
        val batch = model.endsWith(suffix)
        val plain = model.removeSuffix(suffix)
        if (plain == AppSettings.WEB_SEARCH_MODEL) {
            // Searches are counted, not tokens.
            return@map webSearchLabel to webSearchCount.format(usage.inputByModel[model] ?: 0L)
        }
        val label = (if (plain == com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET) sonnetLabel else haikuLabel) +
            if (batch) " (Batch)" else ""
        label to format.inOut(usage.inputByModel[model] ?: 0L, usage.outputByModel[model] ?: 0L)
    }
    // This month and up to eleven before it, newest first: the month and its total, then how it splits.
    val months = (listOf(AppSettings.MonthUsage(usage.month, usage.inputByModel, usage.outputByModel)) + history).map { month ->
        val total = month.usd()
        val title = format.month(month.month) + " · " + format.money(total)
        val detail = if (month.usdOnly != null) {
            null
        } else {
            val batch = month.batchUsd()
            monthDetail.format(format.usd(total - batch), format.usd(batch), (month.sonnetShare() * 100).toInt())
        }
        title to detail
    }
    val resetMonth = stringResource(R.string.claude_usage_reset_month)
    val resetAll = stringResource(R.string.claude_usage_reset_all)
    val resetAllConfirm = stringResource(R.string.claude_usage_reset_all_confirm)
    KoWindow(
        title = stringResource(R.string.claude_usage),
        onBack = { if (confirmingReset) confirmingReset = false else onDismiss() },
        overlay = {
            if (confirmingReset) {
                ConfirmationAlert(
                    hazeState = hazeState,
                    title = resetAll,
                    description = resetAllConfirm,
                    confirmLabel = resetAll,
                    onConfirm = {
                        AppSettings.resetClaudeUsage(everything = true)
                        confirmingReset = false
                    },
                    onDismiss = { confirmingReset = false },
                    frosted = false,
                )
            }
        },
    ) {
        windowText(totalLine + "\n" + todayLine)
        if (models.isNotEmpty()) {
            windowCard(rows = models, header = byModelTitle) { (label, tokens) -> WindowLine(title = label, detail = tokens) }
        }
        windowCard(rows = months, header = monthsTitle, footer = noteLine) { (title, detail) -> WindowLine(title = title, detail = detail) }
        windowActions(
            listOf(
                WindowButton(label = resetMonth) { AppSettings.resetClaudeUsage(everything = false) },
                WindowButton(label = resetAll, destructive = true) { confirmingReset = true },
            ),
        )
    }
}

/**
 * The last Claude requests and how each went, newest first — what was asked,
 * of which model, and why it failed when it did — in a window of its own.
 * Copy puts the whole log on the clipboard to paste into a bug report;
 * nothing in it is secret (no key, no lyrics).
 */
@Composable
private fun ClaudeLogWindow(onDismiss: () -> Unit) {
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
    val purposeRecommend = stringResource(R.string.claude_purpose_recommend)
    val haikuLabel = stringResource(R.string.claude_model_haiku)
    val sonnetLabel = stringResource(R.string.claude_model_sonnet)
    // Every label the lines can need, resolved up front: the loop below is not
    // where composable calls belong, and a failure kind is one of a few.
    val failureLabels = com.music.bitchord.data.lyrics.ClaudeFailure.entries.associateWith { claudeFailureLabel(it) }
    val format = koFormat()
    val httpMeanings = mapOf(
        400 to stringResource(R.string.claude_http_400),
        401 to stringResource(R.string.claude_http_401),
        403 to stringResource(R.string.claude_http_403),
        404 to stringResource(R.string.claude_http_404),
        413 to stringResource(R.string.claude_http_413),
        429 to stringResource(R.string.claude_http_429),
        500 to stringResource(R.string.claude_http_500),
        529 to stringResource(R.string.claude_http_529),
    )
    val stopMeanings = mapOf(
        "end_turn" to stringResource(R.string.claude_stop_end_turn),
        "max_tokens" to stringResource(R.string.claude_stop_max_tokens),
        "refusal" to stringResource(R.string.claude_stop_refusal),
        "tool_use" to stringResource(R.string.claude_stop_tool_use),
        "pause_turn" to stringResource(R.string.claude_stop_pause_turn),
    )
    val inOutLabel = stringResource(R.string.ko_tokens_in_out)
    val purposeBackground = stringResource(R.string.song_background_purpose)
    val searchesLabel = stringResource(R.string.song_background_searches)
    // The copy keeps the raw values, for a bug report; the screen says what they mean.
    val raw = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }
    val copyLines = entries.map { entry ->
        buildString {
            append(raw.format(Date(entry.at))).append(" · ").append(entry.purpose).append(" · ").append(entry.model)
            append('\n').append(entry.failure ?: "ok")
            if (entry.linesAsked != null) append(" · ").append(entry.linesGot?.toString() ?: "-").append('/').append(entry.linesAsked)
            if (entry.elapsedMs > 0) append(" · ").append(String.format(Locale.US, "%.1fs", entry.elapsedMs / 1000.0))
            entry.httpCode?.let { append(" · HTTP ").append(it) }
            entry.stopReason?.let { append(" · ").append(it) }
            if (entry.inputTokens != null || entry.outputTokens != null) {
                append(" · in ").append(entry.inputTokens ?: 0).append("/out ").append(entry.outputTokens ?: 0)
            }
            entry.searches?.takeIf { it > 0 }?.let { append(" · searches ").append(it) }
            entry.detail?.let { append('\n').append("  ").append(it) }
        }
    }
    val lines = entries.map { entry ->
        val purpose = when (entry.purpose) {
            ClaudeDiagnostics.PURPOSE_TRANSLATE -> purposeTranslate
            ClaudeDiagnostics.PURPOSE_MEANING -> purposeMeaning
            ClaudeDiagnostics.PURPOSE_ORIGINAL_NAME -> purposeName
            ClaudeDiagnostics.PURPOSE_READING -> purposeReading
            ClaudeDiagnostics.PURPOSE_BATCH -> purposeBatch
            ClaudeDiagnostics.PURPOSE_RECOMMEND -> purposeRecommend
            ClaudeDiagnostics.PURPOSE_BACKGROUND -> purposeBackground
            else -> entry.purpose
        }
        val model = when (entry.model) {
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET -> sonnetLabel
            com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_HAIKU -> haikuLabel
            else -> entry.model
        }
        val outcome = entry.failureKind?.let { "✗ ${failureLabels[it].orEmpty()}" } ?: "✓ $okText"
        val head = format.time(entry.at, seconds = true) + " · " + purpose + " · " + model
        val body = buildString {
            append(outcome)
            if (entry.linesAsked != null) {
                append(" · ").append(entry.linesGot?.toString() ?: "-").append('/').append(entry.linesAsked)
            }
            if (entry.elapsedMs > 0) append(" · ").append(String.format(Locale.US, "%.1fs", entry.elapsedMs / 1000.0))
            entry.httpCode?.let { code ->
                append(" · HTTP ").append(code)
                (httpMeanings[code] ?: httpMeanings[(code / 100) * 100])?.let { append(" (").append(it).append(')') }
            }
            entry.stopReason?.let { append(" · ").append(stopMeanings[it] ?: it) }
            if (entry.inputTokens != null || entry.outputTokens != null) {
                append(" · ").append(inOutLabel.format(format.tokens(entry.inputTokens ?: 0L), format.tokens(entry.outputTokens ?: 0L)))
            }
            entry.searches?.takeIf { it > 0 }?.let { append(" · ").append(searchesLabel.format(it)) }
            entry.detail?.let { append('\n').append(it) }
        }
        head to body
    }
    val subtitle = if (entries.isEmpty()) {
        stringResource(R.string.claude_log_empty)
    } else {
        stringResource(R.string.claude_log_subtitle, entries.size, entries.count { it.failed })
    }
    val copyLabel = stringResource(R.string.claude_log_copy)
    val clearLabel = stringResource(R.string.claude_log_clear)
    val deleteFailedText = stringResource(R.string.ko_delete_failed)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    KoWindow(title = stringResource(R.string.claude_log), onBack = onDismiss) {
        windowText(subtitle)
        if (lines.isNotEmpty()) {
            windowActions(
                listOf(
                    WindowButton(label = copyLabel) {
                        val header = "BitChord KO ${com.music.bitchord.BuildConfig.VERSION_NAME} · Claude log (${entries.size})"
                        val text = (listOf(header) + copyLines).joinToString("\n\n")
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Claude log", text))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    WindowButton(label = clearLabel, destructive = true) {
                        scope.launch {
                            try {
                                ClaudeDiagnostics.clear()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Toast.makeText(context.applicationContext, deleteFailedText, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ),
            )
            windowCard(rows = lines) { (head, body) -> WindowLine(title = head, detail = body, detailStyle = mono) }
        }
    }
}

/**
 * Settings' amounts and limits, in one window: how amounts are shown and at
 * what rate, the monthly limit and when to be warned of it, and the daily
 * limit — each a card of choices, picked in place.
 */
@Composable
private fun ClaudeCostWindow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val display by AppSettings.moneyDisplay.collectAsStateWithLifecycle()
    val rate by AppSettings.krwPerUsd.collectAsStateWithLifecycle()
    val appliedRate by AppSettings.krwRate.collectAsStateWithLifecycle()
    val rateMode by AppSettings.krwRateMode.collectAsStateWithLifecycle()
    val rateSource by AppSettings.krwRateSource.collectAsStateWithLifecycle()
    val eximKey by AppSettings.eximApiKey.collectAsStateWithLifecycle()
    val fee by AppSettings.cardFeePercent.collectAsStateWithLifecycle()
    val fetched by AppSettings.fetchedRate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var fetchingNow by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf(com.music.bitchord.download.ExchangeRates.lastError) }
    val monthlyCap by AppSettings.claudeMonthlyCap.collectAsStateWithLifecycle()
    val warnAt by AppSettings.claudeCapWarnPercent.collectAsStateWithLifecycle()
    val dailyCap by AppSettings.claudeDailyCap.collectAsStateWithLifecycle()
    // Read again whenever what is spent changes.
    val usage by AppSettings.claudeUsage.collectAsStateWithLifecycle()
    val today by AppSettings.claudeToday.collectAsStateWithLifecycle()
    val format = koFormat()
    var rateText by remember { mutableStateOf(rate.toString()) }
    val share = remember(usage, monthlyCap) { com.music.bitchord.download.ClaudeBudget.monthShare() }
    val spentToday = remember(today) { AppSettings.claudeSpentToday() }
    val summary = listOfNotNull(
        stringResource(
            R.string.claude_monthly_cap_subtitle,
            if (monthlyCap <= 0.0) stringResource(R.string.claude_monthly_cap_none) else format.limit(monthlyCap),
            format.money(AppSettings.claudeSpentThisMonth()),
        ),
        share?.let { stringResource(R.string.claude_monthly_cap_share, (it * 100).toInt()) },
        if (dailyCap > 0.0) {
            stringResource(R.string.claude_usage_today_cap, format.money(spentToday), format.limit(dailyCap))
        } else {
            stringResource(R.string.claude_usage_today, format.money(spentToday))
        },
    ).joinToString("\n")
    val displays = listOf(AppSettings.MONEY_BOTH, AppSettings.MONEY_KRW, AppSettings.MONEY_USD)
    val displayLabels = mapOf(
        AppSettings.MONEY_BOTH to stringResource(R.string.money_display_both),
        AppSettings.MONEY_KRW to stringResource(R.string.money_display_krw),
        AppSettings.MONEY_USD to stringResource(R.string.money_display_usd),
    )
    val sample = 0.42
    val displaySamples = displays.associateWith { KoFormat(context, it, appliedRate).money(sample) }
    val displayHeader = stringResource(R.string.money_display)
    val rateHeader = stringResource(R.string.krw_rate)
    val rateFooter = stringResource(R.string.krw_rate_footer, AppSettings.MIN_KRW_PER_USD, AppSettings.MAX_KRW_PER_USD)
    val rateValue = stringResource(R.string.krw_rate_value, java.text.NumberFormat.getIntegerInstance().format(rate))
    val rateSave = stringResource(R.string.krw_rate_save)
    val rateInvalid = stringResource(R.string.krw_rate_invalid, AppSettings.MIN_KRW_PER_USD, AppSettings.MAX_KRW_PER_USD)
    val rateSaved = stringResource(R.string.krw_rate_saved)
    val capHeader = stringResource(R.string.claude_monthly_cap)
    val capFooter = stringResource(R.string.claude_monthly_cap_description)
    val noneLabel = stringResource(R.string.claude_monthly_cap_none)
    val warnHeader = stringResource(R.string.cap_warn)
    val warnFooter = stringResource(if (monthlyCap > 0.0) R.string.cap_warn_footer else R.string.cap_warn_needs_cap)
    val warnOff = stringResource(R.string.cap_warn_off)
    val warnOption = stringResource(R.string.cap_warn_option)
    val dailyHeader = stringResource(R.string.daily_cap)
    val dailyFooter = stringResource(R.string.daily_cap_footer)
    val dailyNone = stringResource(R.string.daily_cap_none)
    val won = { value: Double ->
        java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)
    }
    val modeHeader = stringResource(R.string.krw_rate)
    val modes = listOf(AppSettings.RATE_AUTO, AppSettings.RATE_MANUAL)
    val modeLabels = mapOf(
        AppSettings.RATE_AUTO to stringResource(R.string.krw_rate_mode_auto),
        AppSettings.RATE_MANUAL to stringResource(R.string.krw_rate_mode_manual),
    )
    val modeDetails = mapOf(
        AppSettings.RATE_AUTO to stringResource(R.string.krw_rate_mode_auto_detail),
        AppSettings.RATE_MANUAL to stringResource(R.string.krw_rate_mode_manual_detail),
    )
    val sourceHeader = stringResource(R.string.krw_rate_source)
    val sources = listOf(AppSettings.RATE_SOURCE_ECB, AppSettings.RATE_SOURCE_EXIM)
    val sourceLabels = mapOf(
        AppSettings.RATE_SOURCE_ECB to stringResource(R.string.krw_rate_source_ecb),
        AppSettings.RATE_SOURCE_EXIM to stringResource(R.string.krw_rate_source_exim),
        AppSettings.RATE_SOURCE_OPEN_ER to stringResource(R.string.krw_rate_source_open_er),
    )
    val sourceDetails = mapOf(
        AppSettings.RATE_SOURCE_ECB to stringResource(R.string.krw_rate_source_ecb_detail),
        AppSettings.RATE_SOURCE_EXIM to stringResource(R.string.krw_rate_source_exim_detail),
    )
    val stale = fetched.date.isNotBlank() && runCatching {
        java.time.LocalDate.parse(fetched.date).isBefore(java.time.LocalDate.now().minusDays(4))
    }.getOrDefault(false)
    val fetchedLines = if (fetched.krw > 0.0) {
        listOfNotNull(
            stringResource(
                R.string.krw_rate_status,
                won(fetched.krw),
                runCatching { format.time(java.time.LocalDate.parse(fetched.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), withTime = false) }
                    .getOrDefault(fetched.date),
                sourceLabels[fetched.source] ?: fetched.source,
            ) + if (stale) " " + stringResource(R.string.krw_rate_stale) else "",
            stringResource(R.string.krw_rate_fetched_at, format.time(fetched.at)).takeIf { fetched.at > 0 },
        )
    } else {
        listOf(stringResource(R.string.krw_rate_none, won(rate.toDouble())))
    }
    val statusText = (fetchedLines + listOfNotNull(fetchError?.let { stringResource(R.string.krw_rate_error, it) }))
        .joinToString("\n")
    val fetchLabel = stringResource(if (fetchingNow) R.string.krw_rate_fetching else R.string.krw_rate_fetch_now)
    val fetchedToast = stringResource(R.string.krw_rate_fetched)
    val fetchFailedToast = stringResource(R.string.krw_rate_fetch_failed)
    val keyHeader = stringResource(R.string.exim_key)
    val keyHint = stringResource(R.string.exim_key_hint)
    val keyFooter = stringResource(R.string.exim_key_footer)
    val keySaved = stringResource(R.string.exim_key_saved)
    val keySave = stringResource(R.string.exim_key_save)
    val keyRemove = stringResource(R.string.exim_key_remove)
    val keyNow = if (eximKey.isBlank()) null else stringResource(R.string.exim_key_set, eximKey.takeLast(4))
    val feeHeader = stringResource(R.string.card_fee)
    val feeFooter = stringResource(R.string.card_fee_footer)
    val feeNone = stringResource(R.string.card_fee_none)
    val feeOption = stringResource(R.string.card_fee_option)
    val fees = listOf(0.0, 1.0, 1.5, 2.0, 2.5, 3.0)
    val feeText = { value: Double -> java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value) }
    val appliedText = if (fee > 0.0) {
        stringResource(R.string.krw_rate_applied_fee, won(appliedRate), won(AppSettings.baseKrwRate()), feeText(fee))
    } else {
        stringResource(R.string.krw_rate_applied, won(appliedRate))
    }
    val fetchNow = {
        if (!fetchingNow) {
            fetchingNow = true
            scope.launch {
                val ok = com.music.bitchord.download.ExchangeRates.refresh()
                fetchingNow = false
                fetchError = com.music.bitchord.download.ExchangeRates.lastError
                Toast.makeText(
                    context,
                    if (ok) fetchedToast.format(won(AppSettings.fetchedRate.value.krw)) else fetchFailedToast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val saveKey = {
        AppSettings.setEximApiKey(keyText)
        keyText = ""
        Toast.makeText(context, keySaved, Toast.LENGTH_SHORT).show()
        fetchNow()
    }
    val saveRate = {
        val value = rateText.filter { it.isDigit() }.toIntOrNull()
        if (value != null && AppSettings.setKrwPerUsd(value)) {
            rateText = value.toString()
            Toast.makeText(context, rateSaved.format(java.text.NumberFormat.getIntegerInstance().format(value)), Toast.LENGTH_SHORT).show()
            true
        } else {
            Toast.makeText(context, rateInvalid, Toast.LENGTH_SHORT).show()
            false
        }
    }
    // Only what is on screen counts: the rate when it is typed by hand, the key when the Eximbank is chosen.
    val rateUnsaved = rateMode == AppSettings.RATE_MANUAL && rateText.isNotBlank() && rateText != rate.toString()
    val keyUnsaved = rateMode == AppSettings.RATE_AUTO && rateSource == AppSettings.RATE_SOURCE_EXIM && keyText.isNotBlank()
    var leaving by remember { mutableStateOf(false) }
    KoWindow(
        title = stringResource(R.string.claude_cost_settings),
        onBack = { if ((rateUnsaved || keyUnsaved) && !leaving) leaving = true else onDismiss() },
        overlay = {
            UnsavedWarning(
                shown = leaving,
                onSave = {
                    leaving = false
                    val rateOk = if (rateUnsaved) saveRate() else true
                    if (keyUnsaved) saveKey()
                    // A rate out of range stays on screen to be fixed.
                    if (rateOk) onDismiss()
                },
                onDiscard = {
                    leaving = false
                    onDismiss()
                },
                onStay = { leaving = false },
            )
        },
    ) {
        windowText(summary)
        windowCard(rows = displays, header = displayHeader) { option ->
            WindowChoice(
                label = displayLabels[option].orEmpty(),
                detail = displaySamples[option],
                checked = option == display,
            ) { AppSettings.setMoneyDisplay(option) }
        }
        windowCard(rows = modes, header = modeHeader) { mode ->
            WindowChoice(label = modeLabels[mode].orEmpty(), detail = modeDetails[mode], checked = mode == rateMode) {
                AppSettings.setKrwRateMode(mode)
                if (mode == AppSettings.RATE_AUTO) fetchNow()
            }
        }
        if (rateMode == AppSettings.RATE_AUTO) {
            windowCard(rows = sources, header = sourceHeader) { source ->
                WindowChoice(label = sourceLabels[source].orEmpty(), detail = sourceDetails[source], checked = source == rateSource) {
                    AppSettings.setKrwRateSource(source)
                    fetchNow()
                }
            }
            windowText(statusText)
            windowActions(listOf(WindowButton(label = fetchLabel, enabled = !fetchingNow) { fetchNow() }))
            if (rateSource == AppSettings.RATE_SOURCE_EXIM) {
                windowCard(rows = listOf(Unit), header = keyHeader, footer = keyFooter) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (keyNow != null) {
                            Text(
                                text = keyNow,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        PillTextField(
                            value = keyText,
                            onValueChange = { keyText = it.trim() },
                            placeholder = keyHint,
                            isPassword = true,
                            container = MaterialTheme.colorScheme.background,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                if (keyText.isNotBlank()) saveKey()
                            }),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                windowActions(
                    listOfNotNull(
                        WindowButton(label = keySave, enabled = keyText.isNotBlank()) { saveKey() },
                        WindowButton(label = keyRemove, destructive = true) {
                            AppSettings.setEximApiKey("")
                            fetchNow()
                        }.takeIf { eximKey.isNotBlank() },
                    ),
                )
            }
        } else {
            windowCard(rows = listOf(Unit), header = rateHeader, footer = rateFooter) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = rateValue,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    PillTextField(
                        value = rateText,
                        onValueChange = { typed -> rateText = typed.filter { it.isDigit() }.take(4) },
                        placeholder = AppSettings.DEFAULT_KRW_PER_USD.toString(),
                        container = MaterialTheme.colorScheme.background,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { saveRate() }),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            windowActions(listOf(WindowButton(label = rateSave) { saveRate() }))
        }
        windowCard(rows = fees, header = feeHeader, footer = feeFooter) { option ->
            WindowChoice(
                label = if (option <= 0.0) feeNone else feeOption.format(feeText(option)),
                checked = option == (fees.minByOrNull { kotlin.math.abs(it - fee) } ?: 0.0),
            ) { AppSettings.setCardFeePercent(option) }
        }
        windowText(appliedText)
        windowCard(rows = MONTHLY_CAPS, header = capHeader, footer = capFooter) { cap ->
            WindowChoice(
                label = if (cap <= 0.0) noneLabel else format.limit(cap),
                checked = cap == (MONTHLY_CAPS.minByOrNull { kotlin.math.abs(it - monthlyCap) } ?: 0.0),
            ) { AppSettings.setClaudeMonthlyCap(cap) }
        }
        windowCard(rows = CAP_WARNINGS, header = warnHeader, footer = warnFooter) { percent ->
            WindowChoice(
                label = if (percent <= 0) warnOff else warnOption.format(percent),
                checked = percent == (CAP_WARNINGS.minByOrNull { kotlin.math.abs(it - warnAt) } ?: 80),
                enabled = monthlyCap > 0.0,
            ) { AppSettings.setClaudeCapWarnPercent(percent) }
        }
        windowCard(rows = DAILY_CAPS, header = dailyHeader, footer = dailyFooter) { cap ->
            WindowChoice(
                label = if (cap <= 0.0) dailyNone else format.limit(cap),
                checked = cap == (DAILY_CAPS.minByOrNull { kotlin.math.abs(it - dailyCap) } ?: 0.0),
            ) { AppSettings.setClaudeDailyCap(cap) }
        }
    }
}
