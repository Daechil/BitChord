package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playingRowMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayingRowMatcherTest {

    private fun song(id: String, title: String = "Song", artist: String = "Artist", slot: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, setVideoId = slot)

    private fun lit(rows: List<Song>, current: Song?): List<String> {
        val matches = playingRowMatcher(rows, current)
        return rows.filter(matches).map { it.videoId }
    }

    /** A song and its music video share a name; only the one tapped lights. */
    @Test
    fun `only the played row lights when namesakes share the list`() {
        val track = song("track")
        val video = song("video")
        assertEquals(listOf("video"), lit(listOf(track, video), video))
        assertEquals(listOf("track"), lit(listOf(track, video), track))
    }

    /** An addon's stereo and Atmos rows are two ids under one name. */
    @Test
    fun `an addon's stereo row lights alone when it is the one playing`() {
        val stereo = song("src:a::tidal:1")
        val atmos = song("src:a::tidal:2")
        assertEquals(listOf("src:a::tidal:1"), lit(listOf(stereo, atmos), stereo))
    }

    /** A local file playing over a YouTube page still finds its row by name — the first one. */
    @Test
    fun `with no row by id the first namesake lights`() {
        val playing = song("local:42")
        assertEquals(listOf("yt1"), lit(listOf(song("other", "Else"), song("yt1"), song("yt2")), playing))
    }

    /** A playlist holding the song twice lights the slot it was played from. */
    @Test
    fun `the playlist slot picks between two copies of one id`() {
        val first = song("x", slot = "s1")
        val second = song("x", slot = "s2")
        val rows = listOf(first, second)
        assertEquals(1, rows.filter(playingRowMatcher(rows, second)).size)
        assertEquals("s2", rows.single(playingRowMatcher(rows, second)).setVideoId)
    }

    @Test
    fun `nothing lights with nothing playing`() {
        assertEquals(emptyList<String>(), lit(listOf(song("a")), null))
    }

    /**
     * KO: a single playing over its artist's page, where the album cut is
     * listed: the album row lights by name, and a tap on it opens the player
     * (PlayingRowTap) rather than starting the album cut over.
     */
    @Test
    fun `another upload of the song playing lights by name`() {
        val single = song("single", title = "唱 - Show", artist = "Ado")
        val albumCut = song("album", title = "唱 - Show", artist = "Ado")
        val other = song("other", title = "うっせぇわ - Usseewa", artist = "Ado")
        assertEquals(listOf("album"), lit(listOf(other, albumCut), single))
    }

    @Test
    fun `a list without the player opens nothing`() {
        // Where no player is connected, the row starts as before.
        assertEquals(false, com.music.bitchord.playback.PlayingRowTap.open())
    }
}
