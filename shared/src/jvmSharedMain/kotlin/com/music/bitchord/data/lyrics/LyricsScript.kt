package com.music.bitchord.data.lyrics

/**
 * KO: whether lyrics are, by their letters, already in the language they would be
 * translated into — checked on the phone, nothing sent. A translation asked for on its
 * own (Settings' "자동 번역") skips such a song: Claude is asked whatever the language,
 * and a Korean song translated into Korean is money spent for nothing.
 *
 * Only scripts tell languages apart here: Korean (Hangul), Japanese (kana), Chinese
 * (Han without kana or Hangul). Latin-script targets are never "already there" —
 * English and Spanish share their letters — so those songs are translated as asked.
 */
object LyricsScript {
    private const val MIN_LETTERS = 20

    fun looksAlreadyIn(texts: List<String>, targetLanguageTag: String): Boolean {
        val base = targetLanguageTag.trim().substringBefore('-').substringBefore('_').lowercase()
        var letters = 0
        var hangul = 0
        var kana = 0
        var han = 0
        for (text in texts) for (c in text) {
            if (!c.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                else -> Unit
            }
        }
        // Too few letters to tell: translate, as a tap would.
        if (letters < MIN_LETTERS) return false
        return when (base) {
            // Mostly Hangul: a Korean song, English lines and all.
            "ko" -> hangul * 2 >= letters
            // Any real share of kana: Japanese, kanji and all.
            "ja" -> kana * 5 >= letters
            "zh" -> han * 2 >= letters && kana * 20 < letters && hangul * 20 < letters
            else -> false
        }
    }
}
