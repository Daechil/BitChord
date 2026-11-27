package com.music.bitchord.ui.screens

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * KO: the groups of a long settings page — the KO features page — as chips
 * under its title, each scrolling to its group; and, once that row has
 * scrolled up under the bar, the same chips pinned there, the group being read
 * picked out.
 *
 * The groups name themselves: [SettingsGroup] reports its header here while a
 * page provides [LocalJumpTargets], so a group hidden for want of a Claude key
 * has no chip, and nothing in the (method-size-bound) settings screen lists them.
 */
internal class SettingsJumpTargets {
    /** Group headers in page order. */
    val names = mutableStateListOf<String>()
    private val anchors = HashMap<String, LayoutCoordinates>()

    /** The page's box, the chip row in the page, and the pinned row: for where things are. */
    var page: LayoutCoordinates? = null
    var inPageRow: LayoutCoordinates? = null

    fun anchor(name: String, coordinates: LayoutCoordinates) {
        anchors[name] = coordinates
        if (name !in names) names += name
    }

    fun forget(name: String) {
        anchors.remove(name)
        names.remove(name)
    }

    fun top(name: String): Float? = anchors[name]?.takeIf { it.isAttached }?.positionInRoot()?.y
}

internal val LocalJumpTargets = staticCompositionLocalOf<SettingsJumpTargets?> { null }

/** Reports a group's header to the page's [SettingsJumpTargets], when there is one. */
@Composable
internal fun Modifier.jumpTarget(header: String): Modifier {
    val targets = LocalJumpTargets.current ?: return this
    DisposableEffect(header) { onDispose { targets.forget(header) } }
    return onGloballyPositioned { targets.anchor(header, it) }
}

/** The chip row as it sits in the page, under the title. */
@Composable
internal fun SettingsJumpChipsInPage(targets: SettingsJumpTargets, scrollState: ScrollState, contentPadding: PaddingValues) {
    if (targets.names.size < 2) {
        // Still laid out once so the page knows the row is there; nothing to jump between yet.
        Box(Modifier.onGloballyPositioned { targets.inPageRow = it })
        return
    }
    JumpChipRow(
        targets = targets,
        scrollState = scrollState,
        contentPadding = contentPadding,
        current = null,
        modifier = Modifier.onGloballyPositioned { targets.inPageRow = it },
    )
}

/** The same row pinned under the bar once the page's own has scrolled up out of sight. */
@Composable
internal fun BoxScope.SettingsJumpChipsPinned(targets: SettingsJumpTargets, scrollState: ScrollState, contentPadding: PaddingValues) {
    val density = LocalDensity.current
    val topPad = with(density) { contentPadding.calculateTopPadding().toPx() }
    val pinned by remember(targets, topPad) {
        derivedStateOf {
            scrollState.value // re-read on every scroll
            val row = targets.inPageRow?.takeIf { it.isAttached } ?: return@derivedStateOf false
            val page = targets.page?.takeIf { it.isAttached } ?: return@derivedStateOf false
            targets.names.size >= 2 && row.positionInRoot().y < page.positionInRoot().y + topPad
        }
    }
    val current by remember(targets, topPad) {
        derivedStateOf {
            scrollState.value
            val page = targets.page?.takeIf { it.isAttached } ?: return@derivedStateOf null
            val rowHeight = targets.inPageRow?.takeIf { it.isAttached }?.size?.height ?: 0
            // A group is "being read" once its header has come up to just under the pinned row.
            val line = page.positionInRoot().y + topPad + rowHeight + with(density) { 48.dp.toPx() }
            targets.names.lastOrNull { name -> (targets.top(name) ?: Float.MAX_VALUE) <= line }
                ?: targets.names.firstOrNull()
        }
    }
    if (!pinned) return
    JumpChipRow(
        targets = targets,
        scrollState = scrollState,
        contentPadding = contentPadding,
        current = current,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = contentPadding.calculateTopPadding())
            .background(MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun JumpChipRow(
    targets: SettingsJumpTargets,
    scrollState: ScrollState,
    contentPadding: PaddingValues,
    current: String?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val chipsScroll = rememberScrollState()
    // Keep the group being read in view as the page scrolls past several.
    LaunchedEffect(current) {
        val index = targets.names.indexOf(current)
        if (index > 0 && chipsScroll.maxValue > 0) {
            chipsScroll.animateScrollTo((chipsScroll.maxValue * index / (targets.names.size - 1)).coerceAtMost(chipsScroll.maxValue))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(chipsScroll)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        targets.names.forEach { name ->
            val selected = name == current
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    // TalkBack: a row of tabs, the group being read the selected one.
                    .semantics { this.selected = selected; role = Role.Tab }
                    .clickable {
                        scope.launch {
                            val header = targets.top(name) ?: return@launch
                            val page = targets.page?.takeIf { it.isAttached } ?: return@launch
                            val rowHeight = targets.inPageRow?.takeIf { it.isAttached }?.size?.height ?: 0
                            val landing = page.positionInRoot().y +
                                with(density) { (contentPadding.calculateTopPadding() + 8.dp).toPx() } + rowHeight
                            scrollState.animateScrollBy(header - landing)
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
