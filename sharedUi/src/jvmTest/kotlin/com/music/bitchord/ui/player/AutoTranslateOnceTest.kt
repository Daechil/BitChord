package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoTranslateOnceTest {
    @Test
    fun aSongIsAutoTranslatedOncePerLanguage() {
        assertTrue(AutoTranslateOnce.firstTime("song-a|ko"))
        assertFalse(AutoTranslateOnce.firstTime("song-a|ko"))
        assertTrue(AutoTranslateOnce.firstTime("song-a|en"))
        assertTrue(AutoTranslateOnce.firstTime("song-b|ko"))
    }
}
