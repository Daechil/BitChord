package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeKeyCheckTest {
    @Test
    fun `a Claude key passes, pasted spaces and all, but other keys and stray text do not`() {
        val key = "sk-ant-api03-" + "a".repeat(40)
        assertTrue(ClaudeKeyCheck.looksLikeClaudeKey(key))
        assertTrue(ClaudeKeyCheck.looksLikeClaudeKey(" $key​\n"))
        assertFalse(ClaudeKeyCheck.looksLikeClaudeKey("sk-proj-" + "a".repeat(40)))
        assertFalse(ClaudeKeyCheck.looksLikeClaudeKey("AIza" + "a".repeat(35)))
        assertFalse(ClaudeKeyCheck.looksLikeClaudeKey("sk-ant-"))
        assertFalse(ClaudeKeyCheck.looksLikeClaudeKey("내 키"))
    }
}
