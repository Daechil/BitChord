package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshRadioTest {

    private fun song(id: String, title: String, artist: String = "Artist $id") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private val a = song("a", "Alpha")
    private val b = song("b", "Bravo")
    private val c = song("c", "Charlie")
    private val d = song("d", "Delta")
    private val e = song("e", "Echo")

    @Test
    fun `nothing heard leaves the station as it came`() {
        val result = FreshRadio.order(listOf(a, b, c), emptyList(), drop = false)
        assertEquals(listOf(a, b, c), result.songs)
        assertEquals(0, result.heard)
    }

    @Test
    fun `heard songs go after the unheard, longest unheard first`() {
        val heard = listOf(FreshRadio.Heard(a, lastDay = 20), FreshRadio.Heard(c, lastDay = 10))
        val result = FreshRadio.order(listOf(a, b, c, d), heard, drop = false)
        assertEquals(listOf(b, d, c, a), result.songs)
        assertEquals(2, result.heard)
        assertFalse(result.dropped)
    }

    @Test
    fun `drop leaves heard songs out when enough are new`() {
        val heard = listOf(FreshRadio.Heard(a, lastDay = 20))
        val result = FreshRadio.order(listOf(a, b, c, d), heard, drop = true)
        assertEquals(listOf(b, c, d), result.songs)
        assertTrue(result.dropped)
    }

    @Test
    fun `drop keeps heard songs last when too few would be left`() {
        val heard = listOf(
            FreshRadio.Heard(a, 5), FreshRadio.Heard(b, 3), FreshRadio.Heard(c, 4),
        )
        val result = FreshRadio.order(listOf(a, b, c, d, e), heard, drop = true)
        // Two new songs is under MIN_FRESH, so the station is filled from the heard ones.
        assertEquals(listOf(d, e, b, c, a), result.songs)
        assertFalse(result.dropped)
    }

    @Test
    fun `another upload of a heard recording counts as heard`() {
        val official = song("x1", "Lemon", "Kenshi Yonezu")
        val video = song("x2", "Lemon (Official Video)", "Kenshi Yonezu")
        val result = FreshRadio.order(listOf(video, b), listOf(FreshRadio.Heard(official, 1)), drop = false)
        assertEquals(listOf(b, video), result.songs)
        assertEquals(1, result.heard)
    }

    @Test
    fun `a different song by the same artist is not heard`() {
        val heard = song("y1", "Lemon", "Kenshi Yonezu")
        val other = song("y2", "KICK BACK", "Kenshi Yonezu")
        val result = FreshRadio.order(listOf(other), listOf(FreshRadio.Heard(heard, 1)), drop = true)
        assertEquals(listOf(other), result.songs)
        assertEquals(0, result.heard)
    }
}
