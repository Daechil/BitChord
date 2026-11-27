package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyClaudeMatchTest {
    private fun track(title: String, artist: String, seconds: Int = 0) =
        SpotifyTrack(id = "t", title = title, artist = artist, album = null, durationMs = seconds * 1000, imageUrl = null)
    private fun song(title: String, artist: String, length: String? = null) =
        Song(videoId = "v$title$artist", title = title, artist = artist, thumbnailUrl = null, durationText = length)

    @Test
    fun `the rules' pick stands only for the same performer and version`() {
        assertTrue(SpotifyClaudeMatch.plainlySame(track("Pretender", "Official HIGE DANdism"), song("Pretender", "Official Hige Dandism")))
        assertTrue(SpotifyClaudeMatch.plainlySame(track("Seven (feat. Latto)", "Jung Kook, Latto"), song("Seven (feat. Latto)", "Jung Kook")))
        // Another singer's cover of the right title.
        assertFalse(SpotifyClaudeMatch.plainlySame(track("Yoru ni Kakeru", "YOASOBI"), song("夜に駆ける", "Hiroki Moriuchi")))
        // The original where the Spotify track is another version.
        assertFalse(SpotifyClaudeMatch.plainlySame(track("Cupid - Twin Ver.", "FIFTY FIFTY"), song("Cupid", "FIFTY FIFTY")))
        assertTrue(SpotifyClaudeMatch.plainlySame(track("Cupid - Twin Ver.", "FIFTY FIFTY"), song("Cupid (Twin Ver.)", "FIFTY FIFTY")))
    }

    @Test
    fun `Claude's choice is held to a real result, the same recording and the length`() {
        val candidates = listOf(song("Lemon", "Kenshi Yonezu", "4:15"), song("Lemon", "Cover Singer", "3:40"))
        val lemon = track("Lemon", "Kenshi Yonezu", 255)
        assertEquals(candidates[0], SpotifyClaudeMatch.accepted(SpotifyClaudeMatch.Answer(0, "same recording"), candidates, lemon))
        // Another version is never taken, whatever it points at.
        assertNull(SpotifyClaudeMatch.accepted(SpotifyClaudeMatch.Answer(0, "another version"), candidates, lemon))
        // A length 35 seconds off is another recording.
        assertNull(SpotifyClaudeMatch.accepted(SpotifyClaudeMatch.Answer(1, "same recording"), candidates, lemon))
        // A position that is not a result.
        assertNull(SpotifyClaudeMatch.accepted(SpotifyClaudeMatch.Answer(7, "same recording"), candidates, lemon))
    }

    @Test
    fun `the plain title drops what Spotify adds`() {
        assertEquals("Bohemian Rhapsody", SpotifyClaudeMatch.plainTitle("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Seven", SpotifyClaudeMatch.plainTitle("Seven (feat. Latto) - Clean Ver."))
    }

    @Test
    fun `searches turned away are not an answer`() = kotlinx.coroutines.runBlocking {
        val outcome = SpotifyClaudeMatch.match(
            track("God-ish", "PinocchioP", 200),
            useClaude = true,
            search = { throw SpotifyClaudeMatch.SearchRefused(it) },
        )
        assertNull(outcome.song)
        assertEquals(0, outcome.claudeCalls) // nothing to choose from: Claude is not asked
        assertFalse(outcome.settled)
    }

    @Test
    fun `searches that find nothing are the answer, without a call`() = kotlinx.coroutines.runBlocking {
        val outcome = SpotifyClaudeMatch.match(track("Nonexistent", "Nobody", 200), useClaude = true, search = { emptyList() })
        assertNull(outcome.song)
        assertEquals(0, outcome.claudeCalls)
        assertTrue(outcome.settled)
    }

    @Test
    fun `a plain match by the rules is settled`() = kotlinx.coroutines.runBlocking {
        val outcome = SpotifyClaudeMatch.match(
            track("God-ish", "PinocchioP", 200),
            useClaude = true,
            search = { listOf(song("God-ish", "PinocchioP", "3:20")) },
        )
        assertEquals("God-ish", outcome.song?.title)
        assertTrue(outcome.settled)
    }
}
