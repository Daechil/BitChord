package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexedAnswerTest {
    private val sources = listOf("I don't know", "what you want from me", "Hope")
    private fun parse(json: String) =
        LyricsTranslation.parseTranslation(json, sources, LyricsTranslation.AnswerShape.INDEXED)

    @Test
    fun oneItemPerLineIsTaken() {
        val r = parse("""{"source_language":"en","declined_reason":"","translations":[{"i":0,"t":"몰라"},{"i":1,"t":"네가 원하는 게 뭔지"},{"i":2,"t":"희망"}]}""")
        assertEquals(listOf("몰라", "네가 원하는 게 뭔지", "희망"), r.value?.translations)
    }

    @Test
    fun aLineSplitInTwoIsJoinedBack() {
        val r = parse("""{"source_language":"en","declined_reason":"","translations":[{"i":0,"t":"몰라"},{"i":1,"t":"네가 원하는 게"},{"i":1,"t":"뭔지"},{"i":2,"t":"희망"}]}""")
        assertEquals(listOf("몰라", "네가 원하는 게 뭔지", "희망"), r.value?.translations)
    }

    @Test
    fun outOfOrderItemsGoUnderTheirOwnLine() {
        val r = parse("""{"source_language":"en","declined_reason":"","translations":[{"i":2,"t":"희망"},{"i":0,"t":"몰라"},{"i":1,"t":"뭔지"}]}""")
        assertEquals(listOf("몰라", "뭔지", "희망"), r.value?.translations)
    }

    @Test
    fun aMissingLineIsAMismatch() {
        val r = parse("""{"source_language":"en","declined_reason":"","translations":[{"i":0,"t":"몰라"},{"i":2,"t":"희망"}]}""")
        assertNull(r.value)
        assertEquals(ClaudeFailure.LINE_MISMATCH, r.failure)
    }

    @Test
    fun aNumberPastTheEndIsAMismatch() {
        val r = parse("""{"source_language":"en","declined_reason":"","translations":[{"i":0,"t":"a"},{"i":1,"t":"b"},{"i":2,"t":"c"},{"i":3,"t":"d"}]}""")
        assertEquals(ClaudeFailure.LINE_MISMATCH, r.failure)
    }

    @Test
    fun plainStringsStillReadForOlderBatches() {
        val r = LyricsTranslation.parseTranslation(
            """{"source_language":"en","declined_reason":"","translations":["몰라","뭔지","희망"]}""",
            sources, LyricsTranslation.AnswerShape.ARRAY,
        )
        assertEquals(listOf("몰라", "뭔지", "희망"), r.value?.translations)
    }
}
