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

    @Test
    fun namesInMixedOrOtherScriptsFallBackOnTheLength() {
        assertTrue(same("Pretender", "Official Hige Dandism", 327_000, "Pretender", "Official髭男dism", 326.0))
        assertTrue(same("晴天", "周杰倫", 270_000, "晴天", "周杰伦", 269.0))
        assertFalse(same("Spring Day", "BTS", 275_000, "Spring Day (Japanese ver.)", "BTS", 274.0))
    }

    private fun entry(a: String, aa: String, am: Long, b: String, bb: String, bm: Long) = SongMatch.sameEntry(a, aa, am, b, bb, bm)

    @Test
    fun oneSongFiledTwiceIsTheSameEntry() {
        // A single and its album cut, a repackage, a feature credit in the title.
        assertTrue(entry("Ditto", "NewJeans", 185_000, "Ditto", "NewJeans", 186_000))
        assertTrue(entry("Love wins all", "IU", 271_000, "Love wins all (Remastered)", "IU", 272_000))
        assertTrue(entry("Die With A Smile", "Lady Gaga, Bruno Mars", 251_000, "Die With A Smile (feat. Bruno Mars)", "Lady Gaga", 251_000))
        assertTrue(entry("ピースサイン - Peace Sign", "Kenshi Yonezu", 238_000, "ピースサイン", "米津玄師", 238_000))
    }

    @Test
    fun differentSongsAndVersionsAreNot() {
        assertFalse(entry("Lemon", "Kenshi Yonezu", 255_000, "Lemonade", "Kenshi Yonezu", 255_000))
        assertFalse(entry("Love", "IU", 200_000, "Love Story", "IU", 200_000))
        assertFalse(entry("Dynamite", "BTS", 199_000, "Dynamite (Slow Jam Remix)", "BTS", 199_000))
        assertFalse(entry("Subtitle", "Official HIGE DANDism", 306_000, "Subtitle", "Official HIGE DANDism", 330_000))
        assertFalse(entry("Hello", "Adele", 295_000, "Hello", "Lionel Richie", 296_000))
    }
}
