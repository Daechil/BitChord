package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeRecommendTest {
    @Test
    fun theSameTitleNearTheTopIsTheSongWhateverScriptTheArtistIsIn() {
        assertTrue(ClaudeRecommend.topHitIsSong("Love wins all", "Love wins all"))
        assertTrue(ClaudeRecommend.topHitIsSong("Lemon", "lemon"))
        assertTrue(ClaudeRecommend.topHitIsSong("夜に駆ける", "夜に駆ける"))
    }

    @Test
    fun anotherTitleOrAnotherVersionIsNot() {
        assertFalse(ClaudeRecommend.topHitIsSong("Lemon", "Lemon Tree"))
        assertFalse(ClaudeRecommend.topHitIsSong("Dynamite", "Dynamite (Slow Jam Remix)"))
        assertFalse(ClaudeRecommend.topHitIsSong("Subtitle", "Subtitle (Live)"))
        assertFalse(ClaudeRecommend.topHitIsSong("", ""))
    }

    @Test
    fun theNameCheckStillRefusesAnArtistInAnotherScriptWithNoLength() {
        // Why the top-hit rule exists: with no length, a name written in another
        // script can't be told from another artist by [SongMatch] alone.
        assertFalse(SongMatch.sameSong("Love wins all", "아이유", 0L, "Love wins all", "IU", null))
    }

    @Test
    fun aListingWithItsEnglishNameIsTheSameSong() {
        assertTrue(ClaudeRecommend.topHitIsSong("ピースサイン", "ピースサイン - Peace Sign"))
        assertTrue(ClaudeRecommend.topHitIsSong("Pretender", "Pretender"))
        assertFalse(ClaudeRecommend.topHitIsSong("ピースサイン", "Lemon"))
    }
}
