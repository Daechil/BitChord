package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SongBackgroundsTest {
    private val lyrics = listOf("完璧で究極のアイドル", "金輪際現れない一番星の生まれ変わり")

    private fun answer(background: String, vararg notes: Pair<String, String>, found: Boolean = true): String {
        val noteJson = notes.joinToString(",") { (p, m) -> "{\"phrase\":\"$p\",\"meaning\":\"$m\"}" }
        val text = kotlinx.serialization.json.JsonPrimitive(background).toString()
        return "{\"found\":$found,\"background\":$text,\"notes\":[$noteJson],\"confidence\":\"medium\",\"sources\":[\"namu.wiki\",\"namu.wiki\",\"Wikipedia\"]}"
    }

    @Test
    fun aGoodAnswerIsKept() {
        val value = assertNotNull(SongBackgrounds.parse(answer("「推しの子」 1기 오프닝이에요. 아이돌의 거짓과 사랑을 그려요.", "アイ" to "사랑(愛)과 이름(아이)"), "ko", lyrics, now = 5))
        assertTrue(value.found)
        assertEquals(listOf("namu.wiki", "Wikipedia"), value.sources)
        assertEquals(1, value.notes.size)
        assertEquals(5, value.at)
    }

    @Test
    fun aSentenceRepeatingTheLyricsIsDropped() {
        val value = assertNotNull(SongBackgrounds.parse(answer("오프닝이에요. 가사에 金輪際現れない一番星の生まれ変わり라고 나와요. 사랑을 그려요."), "ko", lyrics))
        assertFalse(value.background.contains("金輪際"))
        assertTrue(value.background.contains("오프닝"))
    }

    @Test
    fun aLongPhraseIsDropped() {
        val value = assertNotNull(SongBackgrounds.parse(answer("오프닝이에요.", "完璧で究極のアイドル、金輪際" to "긴 인용", "推し" to "최애"), "ko", lyrics))
        assertEquals(listOf("推し"), value.notes.map { it.phrase })
    }

    @Test
    fun notFoundKeepsNothingAndLastsAMonth() {
        val value = assertNotNull(SongBackgrounds.parse(answer("무언가", found = false), "ko", lyrics, now = 0))
        assertFalse(value.found)
        assertEquals("", value.background)
        assertTrue(SongBackgrounds.usable(value, now = 29L * 24 * 3600 * 1000))
        assertFalse(SongBackgrounds.usable(value, now = 31L * 24 * 3600 * 1000))
    }

    @Test
    fun anUnreadableAnswerIsNull() {
        assertEquals(null, SongBackgrounds.parse("not json", "ko", lyrics))
    }

    @Test
    fun oneSongOneKeyWhateverTheCredit() {
        assertEquals(
            SongBackgrounds.keyOf("テトリス - Tetoris", "Hiiragi Magnetite - Topic"),
            SongBackgrounds.keyOf("テトリス - Tetoris (feat. Kasane Teto)", "Hiiragi Magnetite"),
        )
        assertTrue(SongBackgrounds.keyOf("Lemon", "米津玄師") != SongBackgrounds.keyOf("Lemon", "Other"))
    }

    @Test
    fun theRequestNamesTheSong() {
        SongBackgrounds.noteSong("vid1", "アイドル", "YOASOBI", "アイドル")
        val context = assertNotNull(SongBackgrounds.context("vid1"))
        assertEquals("アイドル", (context["title"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(null, SongBackgrounds.context("unknown"))
    }
}
