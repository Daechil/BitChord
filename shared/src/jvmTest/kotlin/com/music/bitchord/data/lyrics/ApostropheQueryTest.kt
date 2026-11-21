package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApostropheQueryTest {
    @Test
    fun apostrophesComeOut() {
        assertEquals("Worlds Smallest Violin", "World's Smallest Violin".withoutApostrophes())
        assertEquals("Dont Stop Me Now", "Don\u2019t Stop Me Now".withoutApostrophes())
        assertEquals("Guns N Roses", "Guns N' Roses".withoutApostrophes())
    }

    @Test
    fun lrcRedAsksAgainWithoutThem() {
        assertEquals(
            listOf("World's Smallest Violin AJR", "Worlds Smallest Violin AJR"),
            LrcRed.queries("World's Smallest Violin", "AJR"),
        )
        assertEquals(listOf("Bang! AJR"), LrcRed.queries("Bang!", "AJR"))
    }

    @Test
    fun aHitWithTheApostropheStillMatchesTheNameAsGiven() {
        val hit = LrcRed.Hit(isrc = "USAT22100001", title = "World's Smallest Violin", artist = "AJR", duration = 181.0)
        assertNotNull(LrcRed.best(listOf(hit), "World's Smallest Violin", "AJR", 181_000))
    }
}
