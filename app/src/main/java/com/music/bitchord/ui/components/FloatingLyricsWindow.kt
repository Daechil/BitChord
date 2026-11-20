package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale

/** The step the bar's own offset moves by, in milliseconds. */
private const val OFFSET_STEP_MS = 100

/**
 * Everything about how the floating bar looks, where it sits and when its
 * words come, in one window, with the bar itself drawn at the top as the
 * choices are made: size, background, what is shown, the next line, the
 * edge round the letters, the translation's colour, timing, and position.
 */
@Composable
internal fun FloatingLyricsWindow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val size by AppSettings.floatingLyricsSize.collectAsStateWithLifecycle()
    val opacity by AppSettings.floatingLyricsOpacity.collectAsStateWithLifecycle()
    val show by AppSettings.floatingLyricsShow.collectAsStateWithLifecycle()
    val nextLine by AppSettings.floatingLyricsNextLine.collectAsStateWithLifecycle()
    val edge by AppSettings.floatingLyricsEdge.collectAsStateWithLifecycle()
    val color by AppSettings.floatingLyricsTranslationColor.collectAsStateWithLifecycle()
    val offset by AppSettings.floatingLyricsOffsetMs.collectAsStateWithLifecycle()
    val playerOffset by AppSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val pinned by AppSettings.floatingLyricsPinned.collectAsStateWithLifecycle()

    val sizes = listOf(AppSettings.FLOATING_SMALL, AppSettings.FLOATING_MEDIUM, AppSettings.FLOATING_LARGE)
    val sizeLabels = sizes.associateWith { stringResource(floatingSizeLabelRes(it)) }
    val opacities = listOf(40, 60, 85, 100)
    val shows = listOf(AppSettings.FLOATING_BOTH, AppSettings.FLOATING_ORIGINAL, AppSettings.FLOATING_TRANSLATION)
    val showLabels = shows.associateWith { stringResource(floatingShowLabelRes(it)) }
    val edges = listOf(AppSettings.FLOATING_EDGE_NONE, AppSettings.FLOATING_EDGE_SHADOW, AppSettings.FLOATING_EDGE_OUTLINE)
    val edgeLabels = mapOf(
        AppSettings.FLOATING_EDGE_NONE to stringResource(R.string.floating_edge_none),
        AppSettings.FLOATING_EDGE_SHADOW to stringResource(R.string.floating_edge_shadow),
        AppSettings.FLOATING_EDGE_OUTLINE to stringResource(R.string.floating_edge_outline),
    )
    val colors = AppSettings.FLOATING_COLORS.keys.toList()
    val colorLabels = mapOf(
        "white" to stringResource(R.string.floating_color_white),
        "yellow" to stringResource(R.string.floating_color_yellow),
        "sky" to stringResource(R.string.floating_color_sky),
        "green" to stringResource(R.string.floating_color_green),
        "pink" to stringResource(R.string.floating_color_pink),
        "orange" to stringResource(R.string.floating_color_orange),
    )
    val previewHeader = stringResource(R.string.floating_preview)
    val previewFooter = stringResource(R.string.floating_preview_footer)
    val sample = stringResource(R.string.floating_preview_line)
    val sampleTranslation = stringResource(R.string.floating_preview_translation)
    val sampleNext = stringResource(R.string.floating_preview_next)
    val sizeHeader = stringResource(R.string.floating_lyrics_size)
    val opacityHeader = stringResource(R.string.floating_lyrics_opacity)
    val opacityFooter = stringResource(R.string.floating_lyrics_opacity_description)
    val showHeader = stringResource(R.string.floating_lyrics_show)
    val showFooter = stringResource(R.string.floating_lyrics_show_description)
    val nextTitle = stringResource(R.string.floating_next_line)
    val nextDetail = stringResource(R.string.floating_next_line_detail)
    val edgeHeader = stringResource(R.string.floating_edge)
    val edgeFooter = stringResource(R.string.floating_edge_footer)
    val colorHeader = stringResource(R.string.floating_color)
    val timingHeader = stringResource(R.string.floating_timing)
    val offsetTitle = stringResource(R.string.floating_offset)
    val offsetDetail = stringResource(R.string.floating_offset_detail, formatSeconds(playerOffset))
    val positionHeader = stringResource(R.string.floating_position)
    val pinTitle = stringResource(R.string.floating_pin)
    val pinDetail = stringResource(R.string.floating_pin_detail)
    val resetLabel = stringResource(R.string.floating_reset_position)
    val resetDone = stringResource(R.string.floating_reset_position_done)

    KoWindow(title = stringResource(R.string.floating_settings), onBack = onDismiss) {
        windowCard(rows = listOf(Unit), header = previewHeader, footer = previewFooter) {
            FloatingPreview(
                size = size,
                opacity = opacity,
                show = show,
                nextLine = nextLine,
                edge = edge,
                translationColor = Color(AppSettings.FLOATING_COLORS[color] ?: 0xE6FFFFFF.toInt()),
                line = sample,
                translation = sampleTranslation,
                next = sampleNext,
            )
        }
        windowCard(rows = sizes, header = sizeHeader) {
            WindowChoice(label = sizeLabels[it].orEmpty(), checked = it == size) { AppSettings.setFloatingLyricsSize(it) }
        }
        windowCard(rows = opacities, header = opacityHeader, footer = opacityFooter) {
            WindowChoice(
                label = "$it%",
                checked = it == (opacities.minByOrNull { o -> kotlin.math.abs(o - opacity) } ?: 85),
            ) { AppSettings.setFloatingLyricsOpacity(it) }
        }
        windowCard(rows = shows, header = showHeader, footer = showFooter) {
            WindowChoice(label = showLabels[it].orEmpty(), checked = it == show) { AppSettings.setFloatingLyricsShow(it) }
        }
        windowCard(rows = listOf(Unit)) {
            WindowSwitch(label = nextTitle, detail = nextDetail, checked = nextLine, onChange = AppSettings::setFloatingLyricsNextLine)
        }
        windowCard(rows = edges, header = edgeHeader, footer = edgeFooter) {
            WindowChoice(label = edgeLabels[it].orEmpty(), checked = it == edge) { AppSettings.setFloatingLyricsEdge(it) }
        }
        windowCard(rows = colors, header = colorHeader) { key ->
            WindowChoice(
                label = colorLabels[key] ?: key,
                checked = key == color,
                swatch = Color(AppSettings.FLOATING_COLORS[key] ?: 0xE6FFFFFF.toInt()),
            ) { AppSettings.setFloatingLyricsTranslationColor(key) }
        }
        windowCard(rows = listOf(Unit), header = timingHeader) {
            WindowStepper(
                label = offsetTitle,
                value = formatSeconds(offset),
                detail = offsetDetail,
                canLower = offset > -AppSettings.FLOATING_OFFSET_LIMIT_MS,
                canRaise = offset < AppSettings.FLOATING_OFFSET_LIMIT_MS,
                onLower = { AppSettings.setFloatingLyricsOffsetMs(offset - OFFSET_STEP_MS) },
                onRaise = { AppSettings.setFloatingLyricsOffsetMs(offset + OFFSET_STEP_MS) },
            )
        }
        windowCard(rows = listOf(Unit), header = positionHeader) {
            WindowSwitch(label = pinTitle, detail = pinDetail, checked = pinned, onChange = AppSettings::setFloatingLyricsPinned)
        }
        windowActions(
            listOf(
                WindowButton(label = resetLabel) {
                    com.music.bitchord.lockscreen.FloatingLyrics.resetPosition(context)
                    Toast.makeText(context, resetDone, Toast.LENGTH_SHORT).show()
                },
            ),
        )
    }
}

/** "+0.3초", "0.0초", "−0.5초". */
private fun formatSeconds(ms: Int): String {
    val seconds = String.format(Locale.US, "%.1f", kotlin.math.abs(ms) / 1000.0)
    val sign = when {
        ms > 0 -> "+"
        ms < 0 -> "−"
        else -> ""
    }
    return sign + seconds + if (Locale.getDefault().language == "ko") "초" else " s"
}

/**
 * The floating bar as it will look, drawn in Compose over a light and a dark
 * band so the edge setting can be judged against both: what the overlay
 * itself draws with Android views, at the same sizes and colours.
 */
@Composable
private fun FloatingPreview(
    size: String,
    opacity: Int,
    show: String,
    nextLine: Boolean,
    edge: String,
    translationColor: Color,
    line: String,
    translation: String,
    next: String,
) {
    val scale = when (size) {
        AppSettings.FLOATING_SMALL -> 0.82f
        AppSettings.FLOATING_LARGE -> 1.25f
        else -> 1f
    }
    val main = if (show == AppSettings.FLOATING_TRANSLATION) translation else line
    val sub = translation.takeIf { show == AppSettings.FLOATING_BOTH }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(10.dp))
            // Half light, half dark behind it, as the screens it floats over are.
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to Color(0xFFF2F2F7),
                    0.5f to Color(0xFFF2F2F7),
                    0.5f to Color(0xFF3A3A3C),
                    1f to Color(0xFF3A3A3C),
                ),
            )
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF121212).copy(alpha = opacity.coerceIn(10, 100) / 100f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EdgedText(main, (16 * scale).sp, Color.White, FontWeight.Bold, edge, maxLines = 2)
            if (sub != null) EdgedText(sub, (13 * scale).sp, translationColor, FontWeight.Normal, edge, maxLines = 2)
            if (nextLine) EdgedText(next, (12 * scale).sp, Color.White.copy(alpha = 0.55f), FontWeight.Normal, edge, maxLines = 1)
        }
    }
}

/** Text with the bar's edge: a soft shadow, or the same words stroked in black underneath. */
@Composable
private fun EdgedText(text: String, size: TextUnit, color: Color, weight: FontWeight, edge: String, maxLines: Int) {
    val base = TextStyle(fontSize = size, fontWeight = weight, textAlign = TextAlign.Center)
    Box(contentAlignment = Alignment.Center) {
        if (edge == AppSettings.FLOATING_EDGE_OUTLINE) {
            Text(
                text = text,
                style = base.copy(drawStyle = Stroke(width = 5f)),
                color = Color.Black.copy(alpha = 0.9f),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = text,
            style = if (edge == AppSettings.FLOATING_EDGE_SHADOW) {
                base.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 3f), blurRadius = 8f))
            } else {
                base
            },
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "보통 · 85% · 원문과 번역 · 테두리" — the window's row in Settings. */
@Composable
fun FloatingSettingsSubtitle() {
    val size by AppSettings.floatingLyricsSize.collectAsStateWithLifecycle()
    val opacity by AppSettings.floatingLyricsOpacity.collectAsStateWithLifecycle()
    val show by AppSettings.floatingLyricsShow.collectAsStateWithLifecycle()
    val edge by AppSettings.floatingLyricsEdge.collectAsStateWithLifecycle()
    val offset by AppSettings.floatingLyricsOffsetMs.collectAsStateWithLifecycle()
    val pinned by AppSettings.floatingLyricsPinned.collectAsStateWithLifecycle()
    val parts = listOfNotNull(
        stringResource(floatingSizeLabelRes(size)),
        "$opacity%",
        stringResource(floatingShowLabelRes(show)),
        when (edge) {
            AppSettings.FLOATING_EDGE_SHADOW -> stringResource(R.string.floating_edge_shadow)
            AppSettings.FLOATING_EDGE_OUTLINE -> stringResource(R.string.floating_edge_outline)
            else -> null
        },
        formatSeconds(offset).takeIf { offset != 0 },
        stringResource(R.string.floating_pin).takeIf { pinned },
    )
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
    )
}
