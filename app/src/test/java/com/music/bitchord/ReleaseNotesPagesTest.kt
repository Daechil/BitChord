package com.music.bitchord

import com.music.bitchord.data.ReleaseNotesPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesPagesTest {

    @Test
    fun `sections become pages and the banner stays above them`() {
        val body = """
            > 🧪 **베타 버전이에요.**

            ## 바뀐 내용

            > 베타예요.

            ### 첫 기능
            - 하나
            - 둘

            ### 둘째 기능
            - 셋
        """.trimIndent()
        val notes = ReleaseNotesPages.split(body)
        assertEquals(listOf("첫 기능", "둘째 기능"), notes.pages.map { it.title })
        assertEquals("- 하나\n- 둘", notes.pages[0].markdown)
        assertTrue(notes.intro.contains("베타 버전이에요"))
        // The workflow's own heading says nothing and is left out.
        assertTrue("바뀐 내용" !in notes.intro)
    }

    @Test
    fun `notes without sections are cut at blank lines`() {
        val paragraph = "가".repeat(500)
        val notes = ReleaseNotesPages.split(listOf(paragraph, paragraph, paragraph).joinToString("\n\n"))
        assertEquals(3, notes.pages.size)
        assertTrue(notes.pages.all { it.title == null && it.markdown == paragraph })
    }

    @Test
    fun `nothing gives nothing`() {
        assertEquals(0, ReleaseNotesPages.split(null).pages.size)
        assertEquals(0, ReleaseNotesPages.split("   ").pages.size)
    }
}
