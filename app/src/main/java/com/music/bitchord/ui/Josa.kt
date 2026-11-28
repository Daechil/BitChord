package com.music.bitchord.ui

/**
 * KO: "%1$s(으)로", "%1$s은(는)" … resolved once the blank is filled — "높음으로",
 * "Galaxy S24로" — by the sound the word before ends on. Only where that sound is
 * certain: a Hangul syllable or a digit. A Latin word ("FLAC": 플락? 에프엘에이씨?)
 * keeps the bracketed form, which is never wrong.
 */
object Josa {

    private enum class Ending { OPEN, RIEUL, CLOSED }

    private val PAIRS = Regex("""(\(으\)로|은\(는\)|이\(가\)|을\(를\)|와\(과\)|과\(와\))""")

    /** Quotes and brackets between a word and its particle: “집”으로 reads 집. */
    private const val SKIPPED = "\"'”’」』)]>»"

    fun fix(text: String): String = PAIRS.replace(text) { match ->
        val ending = endingBefore(text, match.range.first) ?: return@replace match.value
        val closed = ending != Ending.OPEN
        when (match.value) {
            "(으)로" -> if (ending == Ending.CLOSED) "으로" else "로"
            "은(는)" -> if (closed) "은" else "는"
            "이(가)" -> if (closed) "이" else "가"
            "을(를)" -> if (closed) "을" else "를"
            else -> if (closed) "과" else "와"
        }
    }

    private fun endingBefore(text: String, at: Int): Ending? {
        var i = at - 1
        while (i >= 0 && text[i] in SKIPPED) i--
        if (i < 0) return null
        val c = text[i]
        if (c in '가'..'힣') {
            return when ((c - '가') % 28) {
                0 -> Ending.OPEN
                8 -> Ending.RIEUL
                else -> Ending.CLOSED
            }
        }
        // 영 일 이 삼 사 오 육 칠 팔 구
        return when (c) {
            '2', '4', '5', '9' -> Ending.OPEN
            '1', '7', '8' -> Ending.RIEUL
            '0', '3', '6' -> Ending.CLOSED
            else -> null
        }
    }
}
