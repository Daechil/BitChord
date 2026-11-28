package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeModelGoneTest {
    @Test
    fun `only a 404 that names the model means the model is gone`() {
        assertTrue(LyricsTranslation.modelGone(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-haiku-5-5"}}"""))
        assertFalse(LyricsTranslation.modelGone(404, """{"type":"error","error":{"type":"not_found_error","message":"Not found"}}"""))
        assertFalse(LyricsTranslation.modelGone(400, """{"type":"error","error":{"type":"invalid_request_error","message":"model: bad"}}"""))
        assertFalse(LyricsTranslation.modelGone(500, "model"))
    }
}
