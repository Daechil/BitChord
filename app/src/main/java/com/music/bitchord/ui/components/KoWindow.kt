package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.music.bitchord.R
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.screens.Chevron
import com.music.bitchord.ui.screens.GROUP_INSET
import com.music.bitchord.ui.screens.ROW_INSET

/** The corner of a card of rows, as Settings draws its groups. */
private val CARD_CORNER = 14.dp

/**
 * A screen of its own, in a window over everything else, for what is too long
 * for an alert: the translation storage, the logs, a playlist's every song.
 * A bar with a back button and the title, then [content] as one scrolling
 * list of Settings-style cards. Back, the bar's button or the system's, is
 * [onBack]: the caller decides whether that closes something open inside or
 * the window itself.
 *
 * A window rather than an alert drawn in the activity, so it also opens over
 * the player's and the menus' own windows; its alerts go in [overlay], drawn
 * inside it, unfrosted (Haze only sees its own window).
 */
@Composable
internal fun KoWindow(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val lightPage = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = lightPage
                    isAppearanceLightNavigationBars = lightPage
                }
            }
        }
        // Slides in from the side as a page does; Back closes it at once.
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInHorizontally { it / 4 } + fadeIn(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column(Modifier.fillMaxSize()) {
                    WindowBar(title = title, onBack = onBack)
                    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .imePadding(),
                        contentPadding = PaddingValues(bottom = bottom + 32.dp),
                        content = content,
                    )
                }
                overlay()
            }
        }
    }
}

@Composable
private fun WindowBar(title: String, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp),
        ) {
            Icon(
                imageVector = SfIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 60.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One card of rows, built item by item so a list of a thousand songs scrolls
 * without laying them all out: [header] above it, [footer] under it, a
 * hairline between rows, rounded at its ends.
 */
internal fun <T> LazyListScope.windowCard(
    rows: List<T>,
    header: String? = null,
    footer: String? = null,
    key: ((T) -> Any)? = null,
    row: @Composable (T) -> Unit,
) {
    item(contentType = "header") {
        if (header != null) {
            Text(
                text = header.uppercase(java.util.Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GROUP_INSET + 4.dp, end = GROUP_INSET, top = 24.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(20.dp))
        }
    }
    val last = rows.lastIndex
    val rowKey: ((Int, T) -> Any)? = if (key == null) null else { _: Int, item: T -> key(item) }
    itemsIndexed(rows, key = rowKey, contentType = { _, _ -> "row" }) { index, item ->
        val shape = when {
            last == 0 -> RoundedCornerShape(CARD_CORNER)
            index == 0 -> RoundedCornerShape(topStart = CARD_CORNER, topEnd = CARD_CORNER)
            index == last -> RoundedCornerShape(bottomStart = CARD_CORNER, bottomEnd = CARD_CORNER)
            else -> RoundedCornerShape(0.dp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = GROUP_INSET)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            row(item)
        }
    }
    if (footer != null) {
        item(contentType = "footer") {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GROUP_INSET + 4.dp, end = GROUP_INSET + 4.dp, top = 8.dp),
            )
        }
    }
}

/** A card with one block of text in it: a summary, an explanation. */
internal fun LazyListScope.windowText(text: String, header: String? = null, footer: String? = null, style: (@Composable () -> TextStyle)? = null) {
    windowCard(rows = listOf(text), header = header, footer = footer) {
        Text(
            text = it,
            modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 12.dp),
            style = style?.invoke() ?: MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A row of a window's card: an optional [mark] in front (✓, G, –), a title
 * and a quieter [detail] under it, and a chevron when it opens something.
 */
@Composable
internal fun WindowLine(
    title: String,
    detail: String? = null,
    mark: String? = null,
    detailStyle: TextStyle? = null,
    /** A figure at the end of the row: "3곡", "1.2 / 100MB". */
    value: String? = null,
    /** A short coloured label at the end of the row, for where something stands. */
    badge: WindowBadge? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) {
            Text(
                text = mark,
                modifier = Modifier.width(24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    text = detail,
                    style = detailStyle ?: MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = badge.label,
                style = MaterialTheme.typography.labelSmall,
                color = badge.color,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badge.color.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/** A short label and its colour, such as "Claude" in green: always words, never the colour alone. */
internal class WindowBadge(val label: String, val color: androidx.compose.ui.graphics.Color)

/**
 * How full something is: a name and "1.2 / 100MB" on one line, a bar under
 * them, orange from 80% and red when full.
 */
@Composable
internal fun WindowMeter(label: String, value: String, fraction: Float) {
    val shown = fraction.coerceIn(0f, 1f)
    val color = when {
        shown >= 1f -> MaterialTheme.colorScheme.error
        shown >= 0.8f -> androidx.compose.ui.graphics.Color(0xFFFF9500)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/** One of a few choices, ticked when it is the one in force; greyed out and inert when not [enabled]. */
@Composable
internal fun WindowChoice(
    label: String,
    checked: Boolean,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 48.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A button as a row: centred, the accent's colour, red when it deletes something. */
@Composable
internal fun WindowAction(label: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = ROW_INSET, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Whatever can be pressed in a window's action card: a label, its look, and what it does. */
internal class WindowButton(
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** A card of [WindowAction]s; nothing at all when [buttons] is empty. */
internal fun LazyListScope.windowActions(buttons: List<WindowButton>, header: String? = null, footer: String? = null) {
    if (buttons.isEmpty()) return
    windowCard(rows = buttons, header = header, footer = footer) {
        WindowAction(label = it.label, destructive = it.destructive, enabled = it.enabled, onClick = it.onClick)
    }
}
