package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * Claude's notes on the lines picked from the lyric sheet, in a [PlayerDrawer]:
 * each line as sung, its translation, and what it means where it stands. Kept
 * once answered, so the same pick opens at once the next time.
 */
@Composable
internal fun LineNotesSheet(
    hazeState: HazeState,
    song: Song,
    lines: List<LyricLine>,
    picked: List<String>,
    targetLanguage: String,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    val currentLines by rememberUpdatedState(lines)
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(song.videoId, picked, targetLanguage) {
        mutableStateOf<LyricsTranslation.LineNotesResult?>(null)
    }
    LaunchedEffect(song.videoId, picked, targetLanguage, attempt) {
        result = null
        result = PlayerPlatform.host.explainLines(song, currentLines, picked, targetLanguage)
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.line_notes_title),
        onDismiss = onDismiss,
        titleGap = 4.dp,
    ) {
        Text(
            text = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 16.dp),
        )
        when (val state = result) {
            null -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(Res.string.line_notes_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            is LyricsTranslation.LineNotesResult.Ready -> LineNotesBody(state)
            LyricsTranslation.LineNotesResult.NoKey -> LineNotesNotice(
                text = stringResource(Res.string.song_meaning_no_key),
                onRetry = null,
            )
            LyricsTranslation.LineNotesResult.Unavailable -> LineNotesNotice(
                text = stringResource(Res.string.line_notes_unavailable),
                onRetry = {
                    haptics.play(Haptic.Tap)
                    attempt++
                },
            )
        }
    }
}

@Composable
private fun LineNotesBody(state: LyricsTranslation.LineNotesResult.Ready) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.notes.forEach { note ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ROW_SHAPE)
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = note.line,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                // A line already in the reader's language comes back as itself;
                // printing it twice says nothing.
                if (note.translation.isNotBlank() && note.translation != note.line) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = note.translation,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
                if (note.note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note.note,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = Color.White.copy(alpha = 0.66f),
                    )
                }
            }
        }
        Text(
            text = stringResource(
                if (state.sonnetStandIn) Res.string.line_notes_footer_sonnet else Res.string.line_notes_footer,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LineNotesNotice(text: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.75f),
        )
        if (onRetry != null) {
            Text(
                text = stringResource(Res.string.song_meaning_retry),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRetry,
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
