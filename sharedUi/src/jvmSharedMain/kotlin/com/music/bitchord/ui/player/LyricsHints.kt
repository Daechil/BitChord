package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** KO: the lyrics discs that get a first-use hint, in the order they are explained. */
internal enum class LyricsHint(val key: String) {
    TRANSLATE("translate"),
    READING("reading"),
    FURIGANA("furigana"),
}

/**
 * KO: which first-use hint is up. A disc that is on screen registers here; the
 * hint shown is the first in [LyricsHint] order that this device has not seen
 * and whose disc is showing — one at a time, and one per visit to the lyrics:
 * once a hint is closed the next waits until the lyrics are opened again,
 * which is when every disc has left the screen and come back.
 */
internal object LyricsHints {
    private var counter = 0L
    private val present = mutableStateMapOf<Long, LyricsHint>()
    private var closedThisVisit by mutableStateOf(false)

    /** The hint on show, if any, so a tap on its own disc can close it. */
    var showing: LyricsHint? by mutableStateOf(null)
        private set

    fun token(): Long = ++counter

    fun register(token: Long, hint: LyricsHint) {
        present[token] = hint
    }

    fun unregister(token: Long) {
        present.remove(token)
        if (present.isEmpty()) closedThisVisit = false
    }

    /** The anchor whose hint is due, or null. Two anchors of one kind: the earlier one. */
    fun due(seen: Set<String>): Long? {
        if (closedThisVisit) return null
        for (hint in LyricsHint.entries) {
            if (hint.key in seen) continue
            present.entries.filter { it.value == hint }.minOfOrNull { it.key }?.let { return it }
        }
        return null
    }

    internal fun shown(hint: LyricsHint) {
        showing = hint
    }

    fun close(hint: LyricsHint) {
        if (showing == hint) showing = null
        closedThisVisit = true
        PlayerSettings.markLyricsHintSeen(hint.key)
    }

    /** A tap on a disc whose hint is up closes it as read. */
    fun used(hint: LyricsHint) {
        if (showing == hint) close(hint)
    }
}

/**
 * KO: [content] with its first-use hint over it, while it is this disc's turn —
 * see [LyricsHints]. Only an [enabled] disc counts as on screen: a hint over a
 * greyed-out disc would explain a button that cannot be pressed yet.
 */
@Composable
internal fun LyricsHintAnchor(hint: LyricsHint, text: String, enabled: Boolean, content: @Composable () -> Unit) {
    val token = remember { LyricsHints.token() }
    DisposableEffect(hint, enabled) {
        if (enabled) LyricsHints.register(token, hint)
        onDispose { LyricsHints.unregister(token) }
    }
    val seen by PlayerSettings.lyricsHintsSeen.collectAsStateWithLifecycle()
    val mine = LyricsHints.due(seen) == token
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(mine) {
        visible = false
        if (!mine) return@LaunchedEffect
        // A beat after the disc appears, not in the middle of the sheet opening.
        delay(HINT_DELAY_MS)
        visible = true
        LyricsHints.shown(hint)
        delay(HINT_SHOWN_MS)
        visible = false
        LyricsHints.close(hint)
    }
    Box {
        content()
        if (visible && LyricsHints.showing == hint) {
            Popup(popupPositionProvider = AboveAnchor, onDismissRequest = { LyricsHints.close(hint) }) {
                HintBubble(text = text, onClick = { LyricsHints.close(hint) })
            }
        }
    }
}

/** The bubble itself: dark text on a near-white card, readable over any cover. */
@Composable
internal fun HintBubble(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp)
            .widthIn(max = 240.dp)
            .shadow(6.dp, shape)
            .background(Color.White.copy(alpha = 0.96f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = Color(0xFF16161A),
            textAlign = TextAlign.Center,
        )
    }
}

/** Centred over the anchor, kept inside the window. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        val above = anchorBounds.top - popupContentSize.height
        // No room above (a disc at the very top): under it instead.
        val y = if (above >= 0) above else anchorBounds.bottom
        return IntOffset(x, y)
    }
}

private const val HINT_DELAY_MS = 800L
private const val HINT_SHOWN_MS = 6_000L
