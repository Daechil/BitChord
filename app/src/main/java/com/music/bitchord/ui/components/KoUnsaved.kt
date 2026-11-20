package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import dev.chrisbanes.haze.HazeState

/**
 * Asked when a window is left with something typed and not saved: save it and
 * leave, leave without it, or stay and keep writing. Drawn in the window's own
 * overlay, so unfrosted.
 */
@Composable
internal fun UnsavedWarning(shown: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onStay: () -> Unit) {
    if (!shown) return
    val hazeState = remember { HazeState() }
    AlertScaffold(hazeState = hazeState, onDismiss = onStay, frosted = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.ko_unsaved_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.ko_unsaved_description),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.ko_unsaved_save), emphasised = true, onClick = onSave)
        AlertRule()
        AlertAction(label = stringResource(R.string.ko_unsaved_discard), emphasised = false, destructive = true, onClick = onDiscard)
        AlertRule()
        AlertAction(label = stringResource(R.string.ko_unsaved_stay), emphasised = false, onClick = onStay)
    }
}
