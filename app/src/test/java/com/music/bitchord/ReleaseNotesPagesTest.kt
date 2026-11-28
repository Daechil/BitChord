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

    @Test
    fun `the release footer is left out`() {
        val body = "## 바뀐 내용\n\n### 기능\n- 하나\n\n---\n\n버전 1.10-ko.79-beta · 빌드 79 · 베타(시험판)\n\n설치: 휴대폰에서 아래 **Assets**의 `a.apk`를 받아서 열어 주세요.\n"
        val notes = ReleaseNotesPages.split(body)
        assertEquals("- 하나", notes.pages.single().markdown)
    }

    @Test
    fun `several releases are labelled and only the first keeps its banner`() {
        val combined = """
            ## v1.10-ko.79-beta

            > 🧪 **베타 버전이에요.**

            ## 바뀐 내용

            ### 새 것
            - 가

            ## v1.10-ko.78-beta

            > 🧪 **베타 버전이에요.**

            ## 바뀐 내용

            > 베타예요.

            ### 고친 것
            - 나
        """.trimIndent()
        val notes = ReleaseNotesPages.split(combined)
        assertEquals(listOf("ko.79 베타", "ko.78 베타"), notes.pages.map { it.version })
        assertEquals(listOf("- 가", "- 나"), notes.pages.map { it.markdown })
        assertTrue(notes.intro.contains("베타 버전이에요"))
        assertEquals(1, Regex("베타 버전이에요").findAll(notes.intro).count())
    }

    @Test
    fun `a tag in front of a title says what kind of change it is`() {
        val notes = ReleaseNotesPages.split(
            "### [새 기능] 일본어 후리가나\n- 가\n\n### [수정] 꺼지던 문제\n- 나\n\n### [개선] 줄바꿈\n- 다\n\n### 태그 없음\n- 라",
        )
        assertEquals(
            listOf(ReleaseNotesPages.Kind.NEW, ReleaseNotesPages.Kind.FIXED, ReleaseNotesPages.Kind.IMPROVED, null),
            notes.pages.map { it.kind },
        )
        assertEquals(listOf("일본어 후리가나", "꺼지던 문제", "줄바꿈", "태그 없음"), notes.pages.map { it.title })
        // The GitHub page's coloured form reads the same.
        assertEquals(
            ReleaseNotesPages.Kind.FIXED to "꺼지던 문제",
            ReleaseNotesPages.kindOf("\$\\color{#B35A00}{\\textsf{수정}}\$ 꺼지던 문제"),
        )
        // Brackets that are not a known tag are part of the title.
        assertEquals(null to "[베타] 무엇", ReleaseNotesPages.kindOf("[베타] 무엇"))
    }
}
