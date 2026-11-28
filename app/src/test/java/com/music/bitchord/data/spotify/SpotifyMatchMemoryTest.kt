package com.music.bitchord.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyMatchMemoryTest {
    @Test
    fun `a full memory lets the oldest go first and songs picked by hand last`() {
        val order = SpotifyMatchMemory.evictionOrder(
            mapOf(
                "picked" to """{"v":"a","at":1,"m":true}""",
                "new" to """{"v":"b","at":300}""",
                "old" to """{"v":"c","at":100}""",
                "none" to "none@200@NO_RESULTS:0",
                "ancient" to "none",
            ),
        )
        assertEquals(listOf("ancient", "old", "none", "new", "picked"), order)
    }

    @Test
    fun `an answer saved while the clock ran ahead grows old once it is put right`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertEquals(false, SpotifyMatchMemory.expired(now - day, now, 7 * day))
        assertEquals(true, SpotifyMatchMemory.expired(now - 8 * day, now, 7 * day))
        // Saved "a year from now": treated as old, not kept for good.
        assertEquals(true, SpotifyMatchMemory.expired(now + 365 * day, now, 7 * day))
        // A few seconds ahead (two clocks a little apart) is fine.
        assertEquals(false, SpotifyMatchMemory.expired(now + 5_000, now, 7 * day))
    }
}
