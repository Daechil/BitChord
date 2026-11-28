package com.music.bitchord

import com.music.bitchord.data.ReleaseNotesPages
import com.music.bitchord.data.ReleaseNotesPages.Kind
import com.music.bitchord.ui.components.highlightsOf
import com.music.bitchord.ui.components.plainText
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNewsTest {
    @Test
    fun `markdown becomes plain reading text`() {
        val md = "- **설정 › 기타**에서 켜요.\n  - [README](https://x)를 보세요.\n\n| a | b |\n|---|---|\n> 베타예요."
        assertEquals("설정 › 기타에서 켜요.\nREADME를 보세요.\n\n베타예요.", plainText(md))
    }

    @Test
    fun `highlights take one of each kind first`() {
        fun page(t: String, k: Kind?) = ReleaseNotesPages.Page(t, "", null, k)
        val pages = listOf(page("a", Kind.NEW), page("b", Kind.NEW), page("c", Kind.FIXED), page("d", Kind.IMPROVED))
        assertEquals(listOf("a", "d", "c"), highlightsOf(pages).map { it.title })
        // No kinds at all: simply the first three.
        assertEquals(listOf("x", "y", "z"), highlightsOf(listOf("x", "y", "z", "w").map { page(it, null) }).map { it.title })
    }
}
