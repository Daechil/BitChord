package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongMatchTest {
    private fun same(title: String, artist: String, ms: Long, foundTitle: String, foundArtist: String, seconds: Double?) =
        SongMatch.sameSong(title, artist, ms, foundTitle, foundArtist, seconds)

    @Test
    fun anotherSongBySameArtistIsRefused() {
        assertFalse(same("ピースサイン - Peace Sign", "Kenshi Yonezu", 237_000, "ゆめくいしょうじょ", "Kenshi Yonezu", 236.0))
        assertFalse(same("Peace Sign", "Kenshi Yonezu", 237_000, "Lemon", "Kenshi Yonezu", 255.0))
    }

    @Test
    fun theSameSongWrittenTwoWaysIsTaken() {
        assertTrue(same("ピースサイン - Peace Sign", "Kenshi Yonezu", 237_000, "ピースサイン", "Kenshi Yonezu", 237.0))
        assertTrue(same("Peace Sign", "Kenshi Yonezu", 237_000, "Peace Sign", "米津玄師", 238.0))
        assertTrue(same("Dracula (Remastered 2011)", "Tame Impala", 205_000, "Dracula", "Tame Impala", 206.0))
        assertTrue(same("golden hour", "JVKE", 209_000, "golden hour", "JVKE", 209.0))
        assertTrue(same("APT.", "ROSÉ & Bruno Mars", 170_000, "APT.", "ROSÉ", 170.0))
    }

    @Test
    fun aDifferentArtistNeedsTheLengthToAgree() {
        assertFalse(same("Peace Sign", "Kenshi Yonezu", 237_000, "Peace Sign", "米津玄師", 250.0))
        assertFalse(same("Hello", "Adele", 295_000, "Hello", "Lionel Richie", 330.0))
    }

    @Test
    fun aFarLengthIsAnotherRecording() {
        assertFalse(same("Bohemian Rhapsody", "Queen", 355_000, "Bohemian Rhapsody", "Queen", 65.0))
    }

    @Test
    fun aRemixOrLiveCutIsAnotherRecording() {
        assertFalse(same("Dynamite", "BTS", 200_000, "Dynamite (Slow Jam Remix)", "BTS", 200.0))
        assertFalse(same("Subtitle", "Official HIGE DANDism", 306_000, "Subtitle (Live at Tokyo Dome)", "Official HIGE DANDism", 306.0))
        assertTrue(same("Dynamite", "BTS", 200_000, "Dynamite (Remastered)", "BTS", 200.0))
    }

    @Test
    fun aCoverByAnotherArtistIsRefused() {
        assertFalse(same("Pretender", "Official Hige Dandism", 327_000, "Pretender", "Tokyo Arrow", 325.0))
    }

    @Test
    fun theSameArtistsExactly() {
        assertTrue(SongMatch.sameArtists("Billie Eilish", "Billie Eilish"))
        assertFalse(SongMatch.sameArtists("Billie Eilish", "Billie Eilish, Justin Bieber"))
    }
}
