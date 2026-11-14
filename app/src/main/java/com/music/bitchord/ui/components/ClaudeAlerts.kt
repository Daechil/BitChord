package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
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
) {
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
                val label = if (model == com.music.bitchord.data.lyrics.LyricsTranslation.CLAUDE_SONNET) sonnetLabel else haikuLabel
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
