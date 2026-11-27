package com.music.bitchord.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.music.bitchord.data.lyrics.Furigana

/**
 * KO: furigana drawn over a lyric line — the reading of each kanji word in
 * small type above it, from [Furigana] markup.
 *
 * The line keeps its own text and layout; a ruby line only makes room above
 * each of its rows ([rubyRoomStyle]) and draws the readings into that room,
 * centred over where the layout put each word. So the sweep, the glow and the
 * line breaks are the line's own, whatever the readings say.
 */
internal class RubyMark(
    /** The word's characters in the line's text: [start] to [end], exclusive. */
    val start: Int,
    val end: Int,
    val reading: String,
)

/** The readings in [markup] placed on [lineText], or null when the markup is not of that line. */
internal fun rubyMarks(lineText: String, markup: String?): List<RubyMark>? {
    markup ?: return null
    val segments = Furigana.segments(markup) ?: return null
    val plain = Furigana.plain(segments)
    if (plain.isEmpty()) return null
    var at = lineText.indexOf(plain).takeIf { it >= 0 } ?: return null
    val marks = mutableListOf<RubyMark>()
    for (segment in segments) {
        val reading = segment.ruby
        if (reading != null) marks += RubyMark(at, at + segment.base.length, reading)
        at += segment.base.length
    }
    return marks.takeIf { it.isNotEmpty() }
}

/** How much taller each row of a ruby line is, for the readings to sit in. */
private const val RUBY_ROOM = 1.42f

/** The readings' size against the line's. */
private const val RUBY_SCALE = 0.42f

/** [style] with room above each row for its readings; the words sit at the bottom of the taller row. */
internal fun rubyRoomStyle(style: TextStyle): TextStyle {
    val height = style.lineHeight.takeIf { it.isSpecified } ?: (style.fontSize * 1.2f)
    return style.copy(
        lineHeight = height * RUBY_ROOM,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
    )
}

/** The readings' own type: small, bold, set on their own. */
internal fun rubyTextStyle(style: TextStyle): TextStyle = style.copy(
    fontSize = style.fontSize * RUBY_SCALE,
    lineHeight = TextUnit.Unspecified,
    lineHeightStyle = null,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Start,
)

/** Each of [marks]' readings laid out once, for [drawRuby]. */
internal fun measureRuby(measurer: TextMeasurer, marks: List<RubyMark>, style: TextStyle): List<TextLayoutResult> {
    val small = rubyTextStyle(style)
    return marks.map { measurer.measure(it.reading, small) }
}

/**
 * Draws [marks] over the words of [layout], which sits at [origin] in this
 * scope. A word broken across two rows takes its reading over the first.
 */
internal fun DrawScope.drawRuby(
    layout: TextLayoutResult,
    marks: List<RubyMark>,
    readings: List<TextLayoutResult>,
    origin: Offset,
    color: Color,
    baseFontPx: Float,
) {
    val length = layout.layoutInput.text.length
    marks.forEachIndexed { index, mark ->
        val reading = readings.getOrNull(index) ?: return@forEachIndexed
        if (mark.start >= length || mark.end > length || mark.end <= mark.start) return@forEachIndexed
        val row = layout.getLineForOffset(mark.start)
        if (row >= layout.lineCount) return@forEachIndexed
        val lastOnRow = minOf(mark.end, layout.getLineEnd(row, visibleEnd = true)) - 1
        if (lastOnRow < mark.start) return@forEachIndexed
        val first = layout.getBoundingBox(mark.start)
        val last = layout.getBoundingBox(lastOnRow)
        val centre = (minOf(first.left, last.left) + maxOf(first.right, last.right)) / 2f
        // The top of the kanji, from the row's baseline: the room above it is where the reading goes.
        val glyphTop = layout.getLineBaseline(row) - baseFontPx * 0.9f
        val top = maxOf(layout.getLineTop(row), glyphTop - reading.size.height * 0.92f)
        drawText(
            textLayoutResult = reading,
            color = color,
            topLeft = Offset(origin.x + centre - reading.size.width / 2f, origin.y + top),
        )
    }
}
