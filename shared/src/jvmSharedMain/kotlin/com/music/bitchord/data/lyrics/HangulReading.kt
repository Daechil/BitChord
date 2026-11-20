package com.music.bitchord.data.lyrics

import java.text.Normalizer

/**
 * Google's romanisation of a lyric line, written in Hangul the way Korean
 * fans write foreign lyrics to sing along — aishiteru -> 아이시테루, nǐ hǎo ->
 * 니 하오 — on the phone, for nothing: the free reading. Japanese (Hepburn)
 * and Mandarin (pinyin) are read; any other language is left to the
 * romanisation as it is.
 *
 * Words that stand in Latin letters in the original line itself — the
 * "baby" and "love" of a J-pop chorus — are kept as written: they are words
 * the singer sings as they are, not romanised ones. A word that does not
 * parse as Japanese or pinyin is kept as well, rather than guessed at.
 */
object HangulReading {
    /** [romanized] (Google's romanisation of [original]) in Hangul; null when [sourceLanguage] is not one this reads. */
    fun convert(romanized: String, original: String, sourceLanguage: String?): String? {
        val language = sourceLanguage?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        val reader: (String) -> String? = when (language) {
            "ja" -> ::japanese
            "zh" -> ::mandarin
            else -> return null
        }
        val asWritten = WORD.findAll(original).map { it.value.lowercase() }.filter { it.all { c -> c.code < 0x250 } }.toSet()
        return WORD.replace(romanized) { match ->
            val word = match.value
            if (word.lowercase() in asWritten) word else reader(word) ?: word
        }
    }

    private val WORD = Regex("[\\p{L}\\p{M}']+")

    // ---- Hangul syllables ----------------------------------------------------

    // Initial consonants, by their index in the Unicode Hangul block.
    private const val G = 0; private const val N = 2; private const val D = 3; private const val R = 5
    private const val M = 6; private const val B = 7; private const val S = 9; private const val SS = 10
    private const val O = 11; private const val J = 12; private const val JJ = 13; private const val CH = 14
    private const val K = 15; private const val T = 16; private const val P = 17; private const val H = 18

    // Vowels.
    private const val A = 0; private const val YA = 2; private const val EO = 4; private const val E = 5
    private const val YEO = 6; private const val YE = 7; private const val OO = 8; private const val WA = 9
    private const val YO = 12; private const val U = 13; private const val WO = 14; private const val WE = 15
    private const val WI = 16; private const val YU = 17; private const val EU = 18; private const val I = 20

    // Final consonants.
    private const val NONE = 0; private const val F_L = 8; private const val F_N = 4; private const val F_M = 16
    private const val F_S = 19; private const val F_NG = 21

    private fun syllable(initial: Int, vowel: Int, final: Int = NONE): Char =
        (0xAC00 + (initial * 21 + vowel) * 28 + final).toChar()

    /** [text] with [final] put under its last syllable, which had none. */
    private fun StringBuilder.closeWith(final: Int): Boolean {
        if (isEmpty()) return false
        val last = this[length - 1].code - 0xAC00
        if (last < 0 || last >= 11172 || last % 28 != NONE) return false
        setCharAt(length - 1, (0xAC00 + last + final).toChar())
        return true
    }

    /** Lower case, tone marks and macrons off; ü (u with a diaeresis) kept apart as v. */
    private fun plain(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
            .replace("u\u0308", "v")
            .replace(Regex("\\p{M}"), "")

    // ---- Japanese ------------------------------------------------------------

    private const val VOWELS = "aiueo"

    private fun vowelOf(c: Char): Int = when (c) {
        'a' -> A; 'i' -> I; 'u' -> U; 'e' -> E; 'o' -> OO
        else -> -1
    }

    /** The onsets Hepburn writes, longest first, and the consonant each is. */
    private val JA_ONSETS: List<Pair<String, Int>> = listOf(
        "ts" to CH, "sh" to S, "ch" to CH,
        "ky" to K, "gy" to G, "ny" to N, "hy" to H, "my" to M, "ry" to R, "by" to B, "py" to P,
        "dy" to D, "ty" to T, "jy" to J,
        "k" to K, "g" to G, "s" to S, "z" to J, "t" to T, "d" to D, "n" to N, "h" to H,
        "b" to B, "p" to P, "m" to M, "r" to R, "j" to J, "f" to P, "v" to B,
        "y" to O, "w" to O,
    )

    private fun japanese(word: String): String? {
        // The apostrophe stays: it marks an ん, as in kon'ya.
        val w = plain(word)
        if (w.isEmpty()) return null
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            if (c == '\'') {
                i++
                continue
            }
            val next = w.getOrNull(i + 1)
            // ん: an n with no vowel (or y) after it closes the syllable before.
            if (c == 'n' && (next == null || (next !in VOWELS && next != 'y'))) {
                if (!out.closeWith(F_N)) out.append(syllable(O, EU, F_N))
                i++
                continue
            }
            // ん written as m before b, m and p.
            if (c == 'm' && next != null && next in "bmp") {
                if (!out.closeWith(F_M)) out.append(syllable(O, EU, F_M))
                i++
                continue
            }
            // っ: a doubled consonant, or the t of "tch".
            if ((c !in VOWELS && c == next) || w.startsWith("tch", i)) {
                out.closeWith(F_S)
                i++
                continue
            }
            val vowel = vowelOf(c)
            if (vowel >= 0) {
                out.append(syllable(O, vowel))
                i++
                continue
            }
            val onset = JA_ONSETS.firstOrNull { w.startsWith(it.first, i) } ?: return null
            val v = w.getOrNull(i + onset.first.length) ?: return null
            val base = vowelOf(v)
            if (base < 0) return null
            val key = onset.first
            val vowelOut = when {
                // Palatalised: kya, shu, nyo...
                key.length == 2 && key[1] == 'y' || key == "sh" -> when (v) {
                    'a' -> YA; 'u' -> YU; 'o' -> YO; 'e' -> YE; else -> I
                }
                key == "y" -> when (v) {
                    'a' -> YA; 'u' -> YU; 'o' -> YO; 'e' -> YE; else -> I
                }
                key == "w" -> when (v) {
                    'a' -> WA; 'i' -> WI; 'e' -> WE; 'o' -> OO; else -> U
                }
                // す, ず, つ: the u is barely sounded.
                v == 'u' && (key == "s" || key == "z" || key == "ts") -> EU
                key == "ch" || key == "j" -> base
                else -> base
            }
            val initial = when {
                key == "f" && v == 'u' -> H
                else -> onset.second
            }
            out.append(syllable(initial, vowelOut))
            i += key.length + 1
        }
        return out.toString()
    }

    // ---- Mandarin ------------------------------------------------------------

    private val ZH_INITIALS = listOf(
        "zh" to J, "ch" to CH, "sh" to S,
        "b" to B, "p" to P, "m" to M, "f" to P, "d" to D, "t" to T, "n" to N, "l" to R,
        "g" to G, "k" to K, "h" to H, "j" to J, "q" to CH, "x" to S, "r" to R,
        "z" to JJ, "c" to CH, "s" to SS,
    )

    /** One piece of a final: a vowel and the consonant under it. */
    private class Piece(val vowel: Int, val final: Int = NONE)

    /** Finals after a consonant, longest first; ü is written v. */
    private val ZH_FINALS: List<Pair<String, List<Piece>>> = listOf(
        "iang" to listOf(Piece(YA, F_NG)), "iong" to listOf(Piece(YU, F_NG)),
        "uang" to listOf(Piece(WA, F_NG)), "ueng" to listOf(Piece(WO, F_NG)),
        "iao" to listOf(Piece(YA), Piece(OO)), "ian" to listOf(Piece(YE, F_N)), "ing" to listOf(Piece(I, F_NG)),
        "uai" to listOf(Piece(WA), Piece(I)), "uan" to listOf(Piece(WA, F_N)), "van" to listOf(Piece(WI), Piece(A, F_N)),
        "ang" to listOf(Piece(A, F_NG)), "eng" to listOf(Piece(EO, F_NG)), "ong" to listOf(Piece(U, F_NG)),
        "ai" to listOf(Piece(A), Piece(I)), "ei" to listOf(Piece(E), Piece(I)), "ao" to listOf(Piece(A), Piece(OO)),
        "ou" to listOf(Piece(EO), Piece(U)), "an" to listOf(Piece(A, F_N)), "en" to listOf(Piece(EO, F_N)),
        "er" to listOf(Piece(EO, F_L)), "ia" to listOf(Piece(YA)), "ie" to listOf(Piece(YE)),
        "in" to listOf(Piece(I, F_N)), "iu" to listOf(Piece(YU)), "ua" to listOf(Piece(WA)),
        "uo" to listOf(Piece(WO)), "ui" to listOf(Piece(U), Piece(I)), "un" to listOf(Piece(U, F_N)),
        "ve" to listOf(Piece(WE)), "ue" to listOf(Piece(WE)), "vn" to listOf(Piece(WI, F_N)),
        "a" to listOf(Piece(A)), "o" to listOf(Piece(OO)), "e" to listOf(Piece(EO)),
        "i" to listOf(Piece(I)), "u" to listOf(Piece(U)), "v" to listOf(Piece(WI)),
    )

    /** Syllables written with y or w in place of a consonant, longest first. */
    private val ZH_YW: List<Pair<String, String>> = listOf(
        "yuan" to "위안", "yang" to "양", "ying" to "잉", "yong" to "융", "wang" to "왕", "weng" to "웡",
        "yao" to "야오", "you" to "유", "yan" to "옌", "yin" to "인", "yue" to "웨", "yun" to "윈",
        "wai" to "와이", "wei" to "웨이", "wan" to "완", "wen" to "원",
        "ya" to "야", "ye" to "예", "yi" to "이", "yu" to "위", "wa" to "와", "wo" to "워", "wu" to "우",
    )

    private fun mandarin(word: String): String? {
        // An apostrophe parts two syllables that could run together: xi'an, chang'e.
        val parts = plain(word).split('\'').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = StringBuilder()
        for (part in parts) out.append(mandarinPart(part) ?: return null)
        return out.toString()
    }

    private fun mandarinPart(w: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val yw = ZH_YW.firstOrNull { (spelling, _) -> w.startsWith(spelling, i) && !takesNextVowel(w, i + spelling.length, spelling) }
            if (yw != null) {
                out.append(yw.second)
                i += yw.first.length
                continue
            }
            val initial = ZH_INITIALS.firstOrNull { w.startsWith(it.first, i) }
            val start = i + (initial?.first?.length ?: 0)
            val final = ZH_FINALS.firstOrNull { (spelling, _) ->
                w.startsWith(spelling, start) && !takesNextVowel(w, start + spelling.length, spelling)
            } ?: return null
            val name = initial?.first.orEmpty()
            val consonant = initial?.second ?: O
            val jqx = name == "j" || name == "q" || name == "x"
            if (jqx && final.first == "uan") {
                // juan, quan, xuan: the ü takes a syllable of its own, as in yuan.
                out.append(syllable(consonant, WI)).append(syllable(O, A, F_N))
            } else {
                final.second.forEachIndexed { index, piece ->
                    var vowel = piece.vowel
                    if (index == 0) {
                        // After j, q, x the u written is ü.
                        if (jqx) {
                            vowel = when (final.first) {
                                "u", "un" -> WI
                                "ue" -> WE
                                else -> vowel
                            }
                        }
                        // zhi, chi, shi, ri, zi, ci, si: the i is no i at all.
                        if (final.first == "i" && name in setOf("zh", "ch", "sh", "r", "z", "c", "s")) vowel = EU
                        // After ㅈ, ㅉ, ㅊ a y-vowel is written plain: jia 자, qie 체.
                        if (consonant == J || consonant == JJ || consonant == CH) {
                            vowel = when (vowel) { YA -> A; YEO -> EO; YE -> E; YO -> OO; YU -> U; else -> vowel }
                        }
                        out.append(syllable(consonant, vowel, piece.final))
                    } else {
                        out.append(syllable(O, vowel, piece.final))
                    }
                }
            }
            i = start + final.first.length
        }
        return out.toString()
    }

    /**
     * Whether a final ending at [end] should give up its last n or g to the
     * syllable after it: "fangan" is fan-gan only if "an" is followed by a
     * vowel; xian-g-... never — so a final ending in n or ng that is followed
     * by a vowel is not taken whole.
     */
    private fun takesNextVowel(word: String, end: Int, spelling: String): Boolean {
        val next = word.getOrNull(end) ?: return false
        if (next !in "aeiouv") return false
        return spelling.endsWith("n") || spelling.endsWith("ng")
    }
}
