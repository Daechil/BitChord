package com.music.bitchord.data.lyrics

/**
 * KO: readings over a Japanese line's kanji — furigana — as Claude writes
 * them and the lyric panel draws them.
 *
 * Claude copies each line and marks every kanji word as `{抜錨|ばつびょう}`;
 * [checked] holds the copy against the original, letter for letter, before a
 * reading may sit over anything. A line whose copy differs gets none, so a
 * reading can never land over the wrong characters. Stored and passed around
 * as that markup, one string per line; [segments] splits it for drawing.
 */
object Furigana {

    /** What the readings are written in: hiragana (true furigana) or Hangul. */
    enum class Script(
        /** Stands where a translation's target language does in its cache key. */
        val tag: String,
    ) {
        HIRAGANA("furigana-hira"),
        HANGUL("furigana-hang"),
        ;

        companion object {
            fun of(tag: String?): Script? = entries.firstOrNull { it.tag == tag }
        }
    }

    /** A run of the line, with the reading drawn over it, if it has one. */
    data class Segment(val base: String, val ruby: String?)

    private const val OPEN = '{'
    private const val BAR = '|'
    private const val CLOSE = '}'

    /** [markup] split into runs, or null when its braces do not pair up. */
    fun segments(markup: String): List<Segment>? {
        val out = mutableListOf<Segment>()
        val plain = StringBuilder()
        var i = 0
        while (i < markup.length) {
            val c = markup[i]
            when (c) {
                OPEN -> {
                    val bar = markup.indexOf(BAR, i + 1)
                    val close = markup.indexOf(CLOSE, i + 1)
                    if (bar < 0 || close < 0 || bar > close) return null
                    val base = markup.substring(i + 1, bar)
                    val ruby = markup.substring(bar + 1, close)
                    if (base.isEmpty() || base.any { it == OPEN || it == BAR } || ruby.any { it == OPEN || it == BAR }) return null
                    if (plain.isNotEmpty()) {
                        out += Segment(plain.toString(), null)
                        plain.clear()
                    }
                    out += Segment(base, ruby.trim().ifEmpty { null })
                    i = close + 1
                }
                BAR, CLOSE -> return null
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        if (plain.isNotEmpty()) out += Segment(plain.toString(), null)
        return out
    }

    /** The line [segments] spell, without their readings. */
    fun plain(segments: List<Segment>): String = segments.joinToString("") { it.base }

    /** [segments] written back as markup, runs without a reading as they are. */
    fun markup(segments: List<Segment>): String = buildString {
        for (segment in segments) {
            val ruby = segment.ruby
            if (ruby == null) append(segment.base) else append(OPEN).append(segment.base).append(BAR).append(ruby).append(CLOSE)
        }
    }

    /**
     * Claude's [answer] for [original], kept only where it is the same line:
     * the markup with every reading that may stand, or null when the copy
     * differs from the line by so much as a letter, or no reading survives.
     *
     * A reading stands over a run with a kanji in it, written wholly in
     * [script] and not just the run again; any other is dropped and its run
     * kept bare. A line that itself holds a brace or a bar is never read.
     */
    fun checked(original: String, answer: String, script: Script): String? {
        if (original.any { it == OPEN || it == BAR || it == CLOSE }) return null
        val parts = segments(answer.trim()) ?: return null
        if (plain(parts) != original.trim()) return null
        val kept = parts.map { part ->
            val ruby = part.ruby
            if (ruby != null && hasKanji(part.base) && ruby != part.base && inScript(ruby, script)) part else Segment(part.base, null)
        }
        // Neighbouring bare runs as one, so a dropped reading leaves the markup as it would have been without it.
        val merged = mutableListOf<Segment>()
        for (part in kept) {
            val last = merged.lastOrNull()
            if (part.ruby == null && last != null && last.ruby == null) {
                merged[merged.lastIndex] = Segment(last.base + part.base, null)
            } else {
                merged += part
            }
        }
        if (merged.none { it.ruby != null }) return null
        return markup(merged)
    }

    /** A kanji, or one of the marks that stand for one: 々 (repeat), 〆, ヶ. */
    fun isKanji(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN || c == '々' || c == '〆' || c == 'ヶ'

    fun hasKanji(text: String): Boolean = text.any(::isKanji)

    private fun isKana(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    private fun inScript(ruby: String, script: Script): Boolean = ruby.all { c ->
        when (script) {
            // The prolonged-sound mark and the iteration marks are written in hiragana readings too.
            Script.HIRAGANA -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HIRAGANA || c == 'ー' || c == 'ゝ' || c == 'ゞ'
            Script.HANGUL -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL || c == ' '
        }
    }

    /**
     * Whether [lines] are Japanese with kanji to read: a kana somewhere, so a
     * Chinese song (kanji alone) is never sent, and a kanji somewhere, or
     * there is nothing to put a reading over.
     */
    fun wanted(lines: List<String>): Boolean {
        var kana = false
        var kanji = false
        for (line in lines) for (c in line) {
            if (!kana && isKana(c)) kana = true
            if (!kanji && isKanji(c)) kanji = true
            if (kana && kanji) return true
        }
        return false
    }
}
