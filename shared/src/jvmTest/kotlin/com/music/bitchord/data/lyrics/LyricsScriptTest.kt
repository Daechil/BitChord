package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Made-up lines only: the check reads letters, not words. */
class LyricsScriptTest {
    private val korean = List(4) { "오늘은 날씨가 맑고 바람이 시원해요" }
    private val koreanWithEnglish = List(3) { "우리는 함께 걸어가요 천천히" } + "oh yeah"
    private val japanese = List(4) { "きょうはてんきがいいですね そらがあおい" }
    private val english = List(4) { "the weather is clear and the wind is cool today" }
    private val chinese = List(4) { "今天天气很好风也很凉快我们一起走吧" }

    @Test
    fun `a Korean song is already Korean, English lines and all`() {
        assertTrue(LyricsScript.looksAlreadyIn(korean, "ko"))
        assertTrue(LyricsScript.looksAlreadyIn(koreanWithEnglish, "ko-KR"))
    }

    @Test
    fun `other languages are translated into Korean`() {
        assertFalse(LyricsScript.looksAlreadyIn(japanese, "ko"))
        assertFalse(LyricsScript.looksAlreadyIn(english, "ko"))
        assertFalse(LyricsScript.looksAlreadyIn(chinese, "ko"))
    }

    @Test
    fun `Japanese and Chinese targets are told apart by kana`() {
        assertTrue(LyricsScript.looksAlreadyIn(japanese, "ja"))
        assertTrue(LyricsScript.looksAlreadyIn(chinese, "zh-CN"))
        assertFalse(LyricsScript.looksAlreadyIn(japanese, "zh-CN"))
    }

    @Test
    fun `Latin targets and short lyrics are always translated`() {
        assertFalse(LyricsScript.looksAlreadyIn(english, "en"))
        assertFalse(LyricsScript.looksAlreadyIn(listOf("안녕"), "ko"))
    }
}
