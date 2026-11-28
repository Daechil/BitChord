package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnglishNameTest {
    @Test
    fun `a Hangul aside leaves the English title, and the first artist is kept`() {
        assertEquals(
            OriginalNames.Name("Through the Night", "IU"),
            OriginalNames.englishName("Through the Night (밤편지)", "IU", "밤편지", "아이유(IU)"),
        )
        assertEquals(
            OriginalNames.Name("Spring Day", "BTS"),
            OriginalNames.englishName("Spring Day", "BTS", "봄날", "BTS (방탄소년단)"),
        )
    }

    @Test
    fun `a bracket that is not Hangul stays`() {
        assertEquals(
            OriginalNames.Name("Seven (feat. Latto)", "Jung Kook"),
            OriginalNames.englishName("Seven (feat. Latto)", "Jung Kook", "Seven (feat. Latto)", "정국"),
        )
    }

    @Test
    fun `the names already tried are no new answer`() {
        assertNull(OriginalNames.englishName("Celebrity", "IU", "Celebrity", "IU"))
        assertNull(OriginalNames.englishName("Hype Boy", "NewJeans", "Hype Boy", "NewJeans"))
    }
}
