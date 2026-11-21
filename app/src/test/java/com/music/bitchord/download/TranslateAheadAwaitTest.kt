package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateAheadAwaitTest {
    private fun job(
        state: String = TranslateAhead.State.WAITING,
        status: String = TranslateAhead.TrackStatus.SENT,
        target: String = "ko",
        speech: String = "auto",
        mode: String = "natural",
        readingStatus: String? = null,
    ) = TranslateAhead.Job(
        id = "j",
        title = "list",
        createdAt = 0,
        model = "m",
        reading = readingStatus != null,
        target = target,
        speech = speech,
        mode = mode,
        state = state,
        tracks = listOf(TranslateAhead.Track(videoId = "v1", title = "t", artist = "a", status = status, readingStatus = readingStatus)),
    )

    private fun awaiting(vararg jobs: TranslateAhead.Job, id: String = "v1", target: String = "ko") =
        TranslateAhead.awaitingTranslation(jobs.toList(), id, target, "auto", "natural")

    @Test
    fun sentSongInWaitingBatchIsAwaited() = assertTrue(awaiting(job()))

    @Test
    fun preparingJobCounts() = assertTrue(awaiting(job(state = TranslateAhead.State.PREPARING)))

    @Test
    fun answeredOrFailedSongIsNotAwaited() {
        assertFalse(awaiting(job(status = TranslateAhead.TrackStatus.TRANSLATED)))
        assertFalse(awaiting(job(status = TranslateAhead.TrackStatus.FAILED)))
        assertFalse(awaiting(job(status = TranslateAhead.TrackStatus.DECLINED)))
    }

    @Test
    fun cancelingOrFinishedJobIsNotAwaited() {
        assertFalse(awaiting(job(state = TranslateAhead.State.CANCELING)))
        assertFalse(awaiting(job(state = TranslateAhead.State.DONE)))
    }

    @Test
    fun otherLanguageOrStyleIsNotAwaited() {
        assertFalse(awaiting(job(target = "en")))
        assertFalse(awaiting(job(speech = "formal")))
        assertFalse(awaiting(job(mode = "literal")))
    }

    @Test
    fun otherSongIsNotAwaited() = assertFalse(awaiting(job(), id = "v2"))

    @Test
    fun onlyThePronunciationOutIsNotAwaited() =
        assertFalse(awaiting(job(status = TranslateAhead.TrackStatus.TRANSLATED, readingStatus = TranslateAhead.TrackStatus.SENT)))

    @Test
    fun olderJobWithoutRecordedStyleCounts() = assertTrue(awaiting(job(target = "", speech = "", mode = "")))
}
