package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.bitchord.data.ReleaseNotesPages
import kotlinx.coroutines.launch

/**
 * KO: a release's notes as pages, one section each — swiped, or stepped
 * through with the arrows, with dots and "2 / 5" saying where the reader is.
 * See [ReleaseNotesPages] for how the notes are cut.
 */
@Composable
internal fun ReleaseNotesPager(notes: String?, modifier: Modifier = Modifier, pageHeight: Dp = 280.dp) {
    val split = remember(notes) { ReleaseNotesPages.split(notes) }
    if (split.pages.isEmpty()) return
    val pagerState = rememberPagerState { split.pages.size }
    val scope = rememberCoroutineScope()
    val ink = MaterialTheme.colorScheme.onSurface
    // RichText leans on LocalContentColor, which a plain Column background does not provide.
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(modifier) {
            if (split.intro.isNotBlank()) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).alpha(0.75f)) {
                    RichText(style = RichTextStyle.Default) { Markdown(split.intro) }
                }
            }
            // A fixed height while there is more than one page, so swiping does not make the card jump.
            val pagerModifier = if (split.pages.size > 1) {
                Modifier.fillMaxWidth().height(pageHeight)
            } else {
                Modifier.fillMaxWidth().heightIn(max = pageHeight)
            }
            HorizontalPager(state = pagerState, modifier = pagerModifier, verticalAlignment = Alignment.Top) { index ->
                val page = split.pages[index]
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    page.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                            color = ink,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    RichText(style = RichTextStyle.Default) { Markdown(page.markdown) }
                }
            }
            if (split.pages.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val current = pagerState.currentPage
                    PagerArrow(
                        forward = false,
                        enabled = current > 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // Dots for a handful of pages; past that they only crowd the row.
                        if (split.pages.size <= 8) {
                            repeat(split.pages.size) { i ->
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ink.copy(alpha = if (i == current) 0.85f else 0.22f)),
                                )
                            }
                        }
                        Text(
                            text = "${current + 1} / ${split.pages.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    PagerArrow(
                        forward = true,
                        enabled = current < split.pages.size - 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(current + 1) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PagerArrow(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.25f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
