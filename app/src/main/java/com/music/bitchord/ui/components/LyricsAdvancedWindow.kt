package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/**
 * The lyrics' finer settings, in one window: what the romanize button reads
 * with, and the listener's own instructions for Claude's translations.
 */
@Composable
internal fun LyricsAdvancedWindow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mode by AppSettings.pronunciationMode.collectAsStateWithLifecycle()
    val saved by AppSettings.claudeInstructions.collectAsStateWithLifecycle()
    val forMeaning by AppSettings.claudeInstructionsForMeaning.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(saved) }
    val modes = listOf(AppSettings.PRONOUNCE_FREE, AppSettings.PRONOUNCE_CLAUDE, AppSettings.PRONOUNCE_ROMAJI)
    val modeLabels = mapOf(
        AppSettings.PRONOUNCE_FREE to stringResource(R.string.pronounce_free),
        AppSettings.PRONOUNCE_CLAUDE to stringResource(R.string.pronounce_claude),
        AppSettings.PRONOUNCE_ROMAJI to stringResource(R.string.pronounce_romaji),
    )
    val modeDetails = mapOf(
        AppSettings.PRONOUNCE_FREE to stringResource(R.string.pronounce_free_detail),
        AppSettings.PRONOUNCE_CLAUDE to stringResource(R.string.pronounce_claude_detail),
        AppSettings.PRONOUNCE_ROMAJI to stringResource(R.string.pronounce_romaji_detail),
    )
    val modeHeader = stringResource(R.string.pronounce_header)
    val furiganaHangul by AppSettings.furiganaHangul.collectAsStateWithLifecycle()
    val furiganaHeader = stringResource(R.string.furigana_header)
    val furiganaFooter = stringResource(R.string.furigana_footer)
    val furiganaHiraLabel = stringResource(R.string.furigana_hiragana)
    val furiganaHiraDetail = stringResource(R.string.furigana_hiragana_detail)
    val furiganaHangulLabel = stringResource(R.string.furigana_hangul)
    val furiganaHangulDetail = stringResource(R.string.furigana_hangul_detail)
    val modeFooter = stringResource(R.string.pronounce_footer)
    val instructionsHeader = stringResource(R.string.instructions_header)
    val instructionsFooter = stringResource(R.string.instructions_footer)
    val hint = stringResource(R.string.instructions_hint)
    val counter = stringResource(R.string.instructions_counter, text.length, AppSettings.MAX_INSTRUCTIONS_CHARS)
    val examplesHeader = stringResource(R.string.instructions_examples)
    val examples = listOf(
        stringResource(R.string.instructions_example_names),
        stringResource(R.string.instructions_example_casual),
        stringResource(R.string.instructions_example_literal),
        stringResource(R.string.instructions_example_no_dang_sin),
    )
    val saveLabel = stringResource(R.string.instructions_save)
    val clearLabel = stringResource(R.string.instructions_clear)
    val savedToast = stringResource(R.string.instructions_saved)
    val meaningTitle = stringResource(R.string.instructions_for_meaning)
    val meaningDetail = stringResource(R.string.instructions_for_meaning_detail)
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    var leaving by remember { mutableStateOf(false) }
    val unsaved = text.trim() != saved
    val save = {
        AppSettings.setClaudeInstructions(text)
        text = AppSettings.claudeInstructions.value
        Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
    }
    KoWindow(
        title = stringResource(R.string.lyrics_advanced),
        // Typed and not saved: asked first, rather than lost to a back press.
        onBack = { if (unsaved && !leaving) leaving = true else onDismiss() },
        overlay = {
            UnsavedWarning(
                shown = leaving,
                onSave = {
                    leaving = false
                    save()
                    onDismiss()
                },
                onDiscard = {
                    leaving = false
                    onDismiss()
                },
                onStay = { leaving = false },
            )
        },
    ) {
        windowCard(rows = modes, header = modeHeader, footer = modeFooter) { option ->
            WindowChoice(label = modeLabels[option].orEmpty(), detail = modeDetails[option], checked = option == mode) {
                AppSettings.setPronunciationMode(option)
            }
        }
        // KO: what the ふ disc writes over the kanji.
        windowCard(rows = listOf(false, true), header = furiganaHeader, footer = furiganaFooter) { hangul ->
            WindowChoice(
                label = if (hangul) furiganaHangulLabel else furiganaHiraLabel,
                detail = if (hangul) furiganaHangulDetail else furiganaHiraDetail,
                checked = hangul == furiganaHangul,
            ) { AppSettings.setFuriganaHangul(hangul) }
        }
        windowCard(rows = listOf(Unit), header = instructionsHeader, footer = instructionsFooter) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(11.dp))
                        .padding(12.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = placeholderColor)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { typed -> text = typed.take(AppSettings.MAX_INSTRUCTIONS_CHARS) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = counter,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = placeholderColor,
                )
            }
        }
        windowActions(
            listOfNotNull(
                WindowButton(label = saveLabel, enabled = unsaved) { save() },
                WindowButton(label = clearLabel, destructive = true) {
                    text = ""
                    AppSettings.setClaudeInstructions("")
                }.takeIf { saved.isNotEmpty() || text.isNotEmpty() },
            ),
        )
        windowCard(rows = examples, header = examplesHeader) { example ->
            WindowLine(title = "+ $example", onClick = {
                val joined = if (text.isBlank()) example else text.trimEnd() + "\n" + example
                text = joined.take(AppSettings.MAX_INSTRUCTIONS_CHARS)
            })
        }
        windowCard(rows = listOf(Unit)) {
            WindowSwitch(
                label = meaningTitle,
                detail = meaningDetail,
                checked = forMeaning,
                onChange = AppSettings::setClaudeInstructionsForMeaning,
            )
        }
    }
}

/** "발음: 무료 한글 · 지시문 있음" — the window's row in Settings. */
@Composable
fun LyricsAdvancedSubtitle() {
    val mode by AppSettings.pronunciationMode.collectAsStateWithLifecycle()
    val instructions by AppSettings.claudeInstructions.collectAsStateWithLifecycle()
    val modeLabel = stringResource(
        when (mode) {
            AppSettings.PRONOUNCE_CLAUDE -> R.string.pronounce_claude
            AppSettings.PRONOUNCE_ROMAJI -> R.string.pronounce_romaji
            else -> R.string.pronounce_free
        },
    )
    Text(
        text = stringResource(
            R.string.lyrics_advanced_subtitle,
            modeLabel,
            stringResource(if (instructions.isBlank()) R.string.instructions_none else R.string.instructions_set),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
    )
}
