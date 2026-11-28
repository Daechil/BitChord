package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.ReleaseNotesPages
import com.music.bitchord.data.ReleaseNotesPages.Kind

/**
 * KO: a release's notes on a whole screen — the few changes that matter most
 * first, then every change grouped as new, improved or fixed, each with a small
 * coloured label (words, not symbols: the colour is a help, never the only
 * sign), its title, and its explanation folded to three lines.
 *
 * [bottom] is what stays under the list: "확인" and "다시는 안 보기" after an
 * update, "닫기" when opened from the update window.
 */
@Composable
internal fun UpdateNewsWindow(
    title: String,
    subtitle: String?,
    notes: String,
    onClose: () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val split = remember(notes) { ReleaseNotesPages.split(notes) }
    var filter by remember(notes) { mutableStateOf<Kind?>(null) }
    val labelled = remember(split) { split.pages.mapNotNull { it.version }.distinct().size > 1 }
    val highlights = remember(split) { highlightsOf(split.pages) }
    val kinds = remember(split) { KIND_ORDER.filter { kind -> split.pages.any { it.kind == kind } } }
    val headerHighlights = stringResource(R.string.update_news_highlights)
    val intro = remember(split) { plainText(split.intro).takeIf { it.isNotBlank() } }
    val groupNames = mapOf(
        Kind.NEW to stringResource(R.string.update_kind_new),
        Kind.IMPROVED to stringResource(R.string.update_kind_improved),
        Kind.FIXED to stringResource(R.string.update_kind_fixed),
        null to stringResource(R.string.update_kind_other),
    )
    val allLabel = stringResource(R.string.update_news_all)
    KoWindow(
        title = title,
        onBack = onClose,
        overlay = {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = bottom,
            )
        },
    ) {
        listOfNotNull(subtitle, intro).joinToString("\n").takeIf { it.isNotBlank() }?.let { windowText(it) }
        if (highlights.size > 1 && filter == null) {
            windowCard(rows = highlights, header = headerHighlights) { page ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    page.kind?.let { KindLabel(it, groupNames.getValue(it)) }
                    Text(
                        text = page.title.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = if (page.kind != null) 10.dp else 0.dp),
                    )
                }
            }
        }
        // The kind chips, only when there is more than one kind to choose between.
        if (kinds.size > 1) {
            item(contentType = "chips") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(allLabel, filter == null) { filter = null }
                    kinds.forEach { kind ->
                        FilterChip(groupNames.getValue(kind), filter == kind) { filter = kind }
                    }
                }
            }
        }
        for (kind in KIND_ORDER + listOf(null)) {
            if (filter != null && kind != filter) continue
            val pages = split.pages.filter { it.kind == kind }
            if (pages.isEmpty()) continue
            // One mark per group, none per item: enough to find a group at a glance.
            val mark = when (kind) { Kind.NEW -> "✨ "; Kind.IMPROVED -> "💡 "; Kind.FIXED -> "🔧 "; null -> "" }
            windowCard(rows = pages, header = "$mark${groupNames.getValue(kind)} ${pages.size}") { page ->
                NewsRow(page, groupNames, labelled)
            }
        }
        // Room for the buttons pinned under the list.
        item(contentType = "bottom") { Spacer(Modifier.height(140.dp)) }
    }
}

/**
 * KO: for the update window, which stays small for its download and install
 * steps: the three changes that matter most, and a way to the whole list.
 */
@Composable
internal fun UpdateNewsHighlights(notes: String, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    val split = remember(notes) { ReleaseNotesPages.split(notes) }
    val highlights = remember(split) { highlightsOf(split.pages) }
    val names = mapOf(
        Kind.NEW to stringResource(R.string.update_kind_new),
        Kind.IMPROVED to stringResource(R.string.update_kind_improved),
        Kind.FIXED to stringResource(R.string.update_kind_fixed),
    )
    Column(modifier.fillMaxWidth()) {
        highlights.forEach { page ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                page.kind?.let { KindLabel(it, names.getValue(it)) }
                Text(
                    text = page.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (page.kind != null) 8.dp else 0.dp),
                )
            }
        }
        if (split.pages.size > highlights.size || highlights.isEmpty()) {
            Text(
                text = stringResource(R.string.update_news_view_all, split.pages.size),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable(role = Role.Button, onClick = onShowAll)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun NewsRow(page: ReleaseNotesPages.Page, names: Map<Kind?, String>, labelled: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (page.kind != null || (labelled && page.version != null)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                page.kind?.let { KindLabel(it, names.getValue(it)) }
                if (labelled && page.version != null) {
                    Text(
                        text = page.version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = if (page.kind != null) 8.dp else 0.dp),
                    )
                }
            }
        }
        page.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        val body = remember(page) { plainText(page.markdown) }
        if (body.isNotBlank()) {
            FoldedText(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                title = page.title,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A short word in the kind's colour, on a faint wash of it. */
@Composable
private fun KindLabel(kind: Kind, name: String) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = kindColor(kind, dark)
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text = name, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** New in green, improved in blue, fixed in orange — lighter on a dark screen, deeper on a light one. */
internal fun kindColor(kind: Kind, dark: Boolean): Color = when (kind) {
    Kind.NEW -> if (dark) Color(0xFF7FD69B) else Color(0xFF1B7F3B)
    Kind.IMPROVED -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1A66D0)
    Kind.FIXED -> if (dark) Color(0xFFFFB86B) else Color(0xFFB35A00)
}

private val KIND_ORDER = listOf(Kind.NEW, Kind.IMPROVED, Kind.FIXED)

/** The first new, improved and fixed change — or the first three, when the notes carry no kinds. */
internal fun highlightsOf(pages: List<ReleaseNotesPages.Page>): List<ReleaseNotesPages.Page> {
    val byKind = KIND_ORDER.mapNotNull { kind -> pages.firstOrNull { it.kind == kind } }
    return (byKind + pages).distinct().take(3)
}

/**
 * KO: Markdown as plain reading text for the folded explanations: no "**", no
 * list dashes or quote marks, links as their words, tables left out.
 */
internal fun plainText(markdown: String): String = markdown.lines()
    .filterNot { it.trimStart().startsWith("|") }
    .map { line ->
        line.trim()
            .removePrefix("> ").removePrefix(">")
            .replace(Regex("^[-*]\\s+"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace("**", "").replace("`", "")
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
