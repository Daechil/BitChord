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
}
