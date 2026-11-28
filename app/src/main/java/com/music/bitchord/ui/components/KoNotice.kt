package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * KO: a short notice near the bottom of the screen, in place of a toast for
 * anything longer than a line — Android 12 and later cut a toast at two lines,
 * and "○○에 70곡을 저장했어요. 못 찾은 4곡은 빠졌어요." lost its second half.
 * Up to four lines, on screen longer the longer it is, gone on a tap; TalkBack
 * reads it as it appears. Hosted in [KoHealthAlerts].
 */
object KoNotice {
    internal class Notice(val text: String)

    internal val current = MutableStateFlow<Notice?>(null)

    fun show(text: String) {
        if (text.isNotBlank()) current.value = Notice(text)
    }
}

@Composable
internal fun KoNoticeHost() {
    val notice = KoNotice.current.collectAsState().value ?: return
    LaunchedEffect(notice) {
        // 4 s, and 50 ms more a character, up to 9 s: time to read it, not to wait for it.
        delay((4_000L + notice.text.length * 50L).coerceAtMost(9_000L))
        if (KoNotice.current.value === notice) KoNotice.current.value = null
    }
    // Clear of the mini player and the tab bar.
    val lift = with(LocalDensity.current) { 150.dp.roundToPx() }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, -lift),
        properties = PopupProperties(focusable = false),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 480.dp)
                .shadow(8.dp, shape)
                .background(MaterialTheme.colorScheme.inverseSurface, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { if (KoNotice.current.value === notice) KoNotice.current.value = null }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 4,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
