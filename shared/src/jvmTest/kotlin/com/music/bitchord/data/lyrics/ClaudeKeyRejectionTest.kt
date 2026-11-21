package com.music.bitchord.data.lyrics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeKeyRejectionTest {
    private val key = "sk-ant-test-key-0000"

    @AfterTest
    fun reset() {
        LyricsTranslation.claudeApiKey = ""
    }

    @Test
    fun refusedKeyIsNotUsable() {
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.rejectKey(key)
        assertFalse(LyricsTranslation.claudeKeyUsable)
        assertTrue(LyricsTranslation.claudeKeyRejected.value)
    }

    @Test
    fun checkingAgainForgetsTheRefusal() {
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.rejectKey(key)
        LyricsTranslation.clearClaudeKeyRejection()
        assertTrue(LyricsTranslation.claudeKeyUsable)
        assertFalse(LyricsTranslation.claudeKeyRejected.value)
    }

    @Test
    fun savingTheSameKeyAgainTriesItAgain() {
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.rejectKey(key)
        LyricsTranslation.claudeApiKey = key
        assertTrue(LyricsTranslation.claudeKeyUsable)
    }

    @Test
    fun noKeyIsNeverUsable() {
        LyricsTranslation.claudeApiKey = ""
        LyricsTranslation.clearClaudeKeyRejection()
        assertFalse(LyricsTranslation.claudeKeyUsable)
    }
}
