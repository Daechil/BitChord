package com.music.bitchord.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.music.bitchord.R
import dev.chrisbanes.haze.HazeState

/**
 * KO: a description cut to [maxLines], with a small "자세히" under it when it
 * did not fit. Tapping it opens the whole text in a window of its own — its
 * own window because these sit in bottom sheets (settings, the KO windows),
 * and an alert drawn in the activity's window would open behind them.
 *
 * Text that fits shows exactly as a plain [Text] would, with nothing added.
 */
@Composable
internal fun FoldedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    /** Heads the full-text window: the row's own title, where it has one. */
    title: String? = null,
) {
    var overflows by remember(text, maxLines) { mutableStateOf(false) }
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> overflows = layout.hasVisualOverflow },
        )
        if (overflows) {
            Text(
                text = stringResource(R.string.folded_more),
                style = style.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    // A button to TalkBack, which says what it opens; a little taller to hit.
                    .clickable(role = Role.Button, onClickLabel = title ?: stringResource(R.string.folded_title)) { open = true }
                    .padding(top = 6.dp, bottom = 6.dp, end = 16.dp),
            )
        }
    }
    if (open) {
        FoldedTextWindow(
            title = title ?: stringResource(R.string.folded_title),
            text = text,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun FoldedTextWindow(title: String, text: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The scaffold draws its own flat scrim; the platform's would double it.
        SideEffect { window?.setDimAmount(0f) }
        // A window of its own has nothing behind it to blur, so the card is a plain surface.
        AlertScaffold(hazeState = remember { HazeState() }, onDismiss = onDismiss, frosted = false) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            AlertRule()
            AlertAction(label = stringResource(R.string.folded_close), emphasised = true, onClick = onDismiss)
        }
    }
}
