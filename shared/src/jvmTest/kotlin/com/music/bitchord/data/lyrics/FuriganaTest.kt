package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuriganaTest {

    private val hira = Furigana.Script.HIRAGANA
    private val hangul = Furigana.Script.HANGUL

    @Test
    fun `splits markup into runs`() {
        val parts = Furigana.segments("{抜錨|ばつびょう}を{見|み}て")
        assertEquals(
            listOf(
                Furigana.Segment("抜錨", "ばつびょう"),
                Furigana.Segment("を", null),
                Furigana.Segment("見", "み"),
                Furigana.Segment("て", null),
            ),
            parts,
        )
        assertEquals("抜錨を見て", Furigana.plain(parts!!))
    }

    @Test
    fun `unpaired braces are not markup`() {
        assertNull(Furigana.segments("{抜錨|ばつびょう を"))
        assertNull(Furigana.segments("抜錨|ばつびょう}を"))
        assertNull(Furigana.segments("{|ばつ}"))
    }

    @Test
    fun `a faithful copy keeps its readings`() {
        assertEquals("{抜錨|ばつびょう}を{見|み}て", Furigana.checked("抜錨を見て", "{抜錨|ばつびょう}を{見|み}て", hira))
    }

    @Test
    fun `a copy that differs by one letter gets no readings`() {
        // Claude "fixed" the kana: the line on screen is not this line.
        assertNull(Furigana.checked("抜錨を見て", "{抜錨|ばつびょう}を{見|み}た", hira))
        // A space lost.
        assertNull(Furigana.checked("君と 僕", "{君|きみ}と{僕|ぼく}", hira))
    }

    @Test
    fun `readings over kana or in the wrong script are dropped`() {
        // Over kana: dropped, the run kept bare and joined to its neighbours.
        assertEquals("{夜|よる}に駆けて", Furigana.checked("夜に駆けて", "{夜|よる}{に|に}駆けて", hira))
        // Katakana where hiragana is asked for.
        assertNull(Furigana.checked("運命", "{運命|ウンメイ}", hira))
        // Hangul asked for and given.
        assertEquals("{運命|운메이}", Furigana.checked("運命", "{運命|운메이}", hangul))
        // A reading that only repeats the word.
        assertNull(Furigana.checked("運命", "{運命|運命}", hira))
    }

    @Test
    fun `song readings and the prolonged mark stand`() {
        assertEquals("{本気|まじ}で", Furigana.checked("本気で", "{本気|まじ}で", hira))
        assertEquals("{珈琲|こーひー}", Furigana.checked("珈琲", "{珈琲|こーひー}", hira))
        assertEquals("{人々|ひとびと}", Furigana.checked("人々", "{人々|ひとびと}", hira))
    }

    @Test
    fun `a line with braces of its own is never read`() {
        assertNull(Furigana.checked("{歌}", "{歌|うた}", hira))
    }

    @Test
    fun `only Japanese with kanji is wanted`() {
        assertTrue(Furigana.wanted(listOf("抜錨を見て")))
        assertFalse(Furigana.wanted(listOf("ひらがなだけ")))
        // Chinese: kanji with no kana.
        assertFalse(Furigana.wanted(listOf("我爱你")))
        assertFalse(Furigana.wanted(listOf("I love you")))
    }

    @Test
    fun `a batch asks for furigana only for Japanese with kanji`() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("furibatch").toFile()
        LyricsTranslation.cacheDir = dir
        LyricsTranslation.keepDir = dir
        val japanese = listOf(LyricLine(0, "抜錨を見て"), LyricLine(1000, "ひらがな"))
        val asked = LyricsTranslation.aheadRequest("f0", "vidf", japanese, "ko", LyricsTranslation.CLAUDE_HAIKU, reading = false, furigana = hira)
        val item = kotlin.test.assertNotNull(asked.item)
        assertEquals(hira.tag, item.furigana)
        assertEquals(hira.tag, item.target)
        // Every line goes, for the readings to have the song around them.
        assertEquals(listOf(0, 1), item.furiganaLines)
        val system = asked.params?.get("system").toString()
        assertTrue(system.contains("furigana") && system.contains("cache_control"))
        // Never with a web search, whatever was asked.
        assertEquals(null, asked.params?.get("tools"))
        val chinese = listOf(LyricLine(0, "我爱你"))
        assertEquals(
            LyricsTranslation.AheadSkip.SAME_LANGUAGE,
            LyricsTranslation.aheadRequest("f1", "vidc", chinese, "ko", LyricsTranslation.CLAUDE_HAIKU, reading = false, furigana = hira).skip,
        )
    }
}
