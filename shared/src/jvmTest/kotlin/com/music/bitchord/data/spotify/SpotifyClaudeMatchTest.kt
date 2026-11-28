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

    @Test
    fun `when Claude cannot answer, the rules' pick still plays`() = kotlinx.coroutines.runBlocking {
        // No key in tests, so the call is refused, as when a limit is reached on a phone.
        val outcome = SpotifyClaudeMatch.match(
            track("God-ish", "PinocchioP", 200),
            useClaude = true,
            search = { listOf(song("God-ish (神っぽいな)", "ピノキオピー", "3:21")) },
        )
        assertEquals("ピノキオピー", outcome.song?.artist)
        assertFalse(outcome.settled) // not remembered: Claude is asked again next time
    }

    @Test
    fun `a grey row says why`() {
        val track = track("Hibana", "DECO*27", 200)
        val songs = listOf(song("ヒバナ", "DECO*27", "4:00"), song("Hibana (Live)", "DECO*27", "3:21"))
        fun answer(choice: Int, relation: String) = SpotifyClaudeMatch.Answer(choice = choice, relation = relation)
        assertEquals(SpotifyClaudeMatch.MissKind.OTHER_VERSION, SpotifyClaudeMatch.whyNot(answer(1, "another version"), songs, track).kind)
        // The same recording, 40 seconds longer: the length is why.
        assertEquals(SpotifyClaudeMatch.Miss(SpotifyClaudeMatch.MissKind.LENGTH, 40), SpotifyClaudeMatch.whyNot(answer(0, "same recording"), songs, track))
        assertEquals(SpotifyClaudeMatch.MissKind.NOT_THERE, SpotifyClaudeMatch.whyNot(answer(-1, "none"), songs, track).kind)
    }

    @Test
    fun `no results and refused searches say so`() = kotlinx.coroutines.runBlocking {
        val empty = SpotifyClaudeMatch.match(track("Nothing", "Nobody", 200), useClaude = true, search = { emptyList() })
        assertEquals(SpotifyClaudeMatch.MissKind.NO_RESULTS, empty.miss?.kind)
        val refused = SpotifyClaudeMatch.match(track("Nothing", "Nobody", 200), useClaude = true, search = { throw SpotifyClaudeMatch.SearchRefused(it) })
        assertEquals(SpotifyClaudeMatch.MissKind.UNREACHABLE, refused.miss?.kind)
    }

    @Test
    fun `with nothing of the right length, the performer's own songs are searched`() = kotlinx.coroutines.runBlocking {
        val asked = mutableListOf<String>()
        SpotifyClaudeMatch.match(
            track("Galaxy", "BOL4", 210),
            useClaude = false,
            search = { q -> asked += q; if (q == "BOL4") listOf(song("우주를 줄게", "볼빨간사춘기", "3:31")) else listOf(song("Galaxy", "Someone", "2:40")) },
        )
        // Without Claude the rules' path stops early; with it, the performer search follows.
        val withClaude = SpotifyClaudeMatch.match(
            track("Galaxy", "BOL4", 210),
            useClaude = true,
            search = { q -> asked += q; if (q == "BOL4") listOf(song("우주를 줄게", "볼빨간사춘기", "3:31")) else listOf(song("Galaxy", "Someone", "2:40")) },
        )
        assertTrue("BOL4" in asked)
        // No key in tests: Claude is refused, and the fallback finds the right-length song.
        assertEquals("우주를 줄게", withClaude.song?.title)
    }

    @Test
    fun `a dash in front of a word does not leave that word out of the search`() {
        assertEquals("ヒバナ Reloaded- DECO*27", SpotifyClaudeMatch.searchable("ヒバナ -Reloaded- DECO*27"))
        assertEquals("初音ミクの消失 DEAD END-", SpotifyClaudeMatch.searchable("初音ミクの消失 -DEAD END-"))
        // A dash inside a word, or standing alone, is left as it is.
        assertEquals("Spider-Man - Remastered", SpotifyClaudeMatch.searchable("Spider-Man - Remastered"))
        assertEquals("Song", SpotifyClaudeMatch.searchable("--Song"))
    }

    @Test
    fun `music videos are looked at only when no song has the length`() = kotlinx.coroutines.runBlocking {
        var videoSearches = 0
        val video = Song(videoId = "mv", title = "シックマン", artist = "Chinozo", thumbnailUrl = null, durationText = "2:58", isVideo = true)
        SpotifyClaudeMatch.match(
            track("シックマン", "Chinozo, GANMI", 177),
            useClaude = true,
            search = { listOf(song("シェーマ", "Chinozo", "3:28")) },
            searchVideos = { videoSearches++; listOf(video) },
        )
        assertEquals(1, videoSearches)
        // A song of the right length found: no video search at all.
        SpotifyClaudeMatch.match(
            track("Lemon", "Kenshi Yonezu", 255),
            useClaude = true,
            search = { listOf(song("Lemon", "Kenshi Yonezu", "4:16")) },
            searchVideos = { videoSearches++; emptyList() },
        )
        assertEquals(1, videoSearches)
    }

    @Test
    fun `the track's own album is opened when the searches miss it`() = kotlinx.coroutines.runBlocking {
        val track = SpotifyTrack(id = "t", title = "ヒバナ -Reloaded-", artist = "DECO*27", album = "アンドロイドガール", durationMs = 206_000, imageUrl = null)
        var albumAsked: Pair<String, String>? = null
        val outcome = SpotifyClaudeMatch.match(
            track,
            useClaude = true,
            search = { listOf(song("HIBANA", "SHUHEI ENDO", "3:51")) },
            searchVideos = { emptyList() },
            searchAlbumTracks = { album, performer ->
                albumAsked = album to performer
                listOf(song("アンドロイドガール", "DECO*27", "3:38"), song("ヒバナ -Reloaded-", "DECO*27", "3:25"))
            },
        )
        assertEquals("アンドロイドガール" to "DECO*27", albumAsked)
        // No key in tests: Claude is refused, and the album's one track of that length stands in.
        assertEquals("ヒバナ -Reloaded-", outcome.song?.title)
    }

    @Test
    fun `a song of the right length but another title does not keep the videos away`() = kotlinx.coroutines.runBlocking {
        val video = Song(videoId = "mv", title = "TheFatRat - Rise Up", artist = "TheFatRat", thumbnailUrl = null, durationText = "2:49", isVideo = true)
        val outcome = SpotifyClaudeMatch.match(
            track("Rise Up", "TheFatRat", 169),
            useClaude = true,
            // "Sunlight" is the right length, so the first round looks no further.
            search = { listOf(song("Sunlight", "TheFatRat, Phaera", "2:50")) },
            searchVideos = { listOf(video) },
            searchAlbumTracks = { _, _ -> emptyList() },
            // Claude says none to Sunlight; offered the video, it takes it.
            askClaude = { candidates, _, _ ->
                val i = candidates.indexOfFirst { it.isVideo }
                if (i < 0) SpotifyClaudeMatch.Answer(choice = -1, relation = "none")
                else SpotifyClaudeMatch.Answer(choice = i, relation = "same recording, another upload")
            },
        )
        assertEquals("mv", outcome.song?.videoId)
        assertEquals(2, outcome.claudeCalls)
    }

    @Test
    fun `same title, performer and length need no Claude`() = kotlinx.coroutines.runBlocking {
        var asked = 0
        val outcome = SpotifyClaudeMatch.match(
            track("September", "Earth, Wind & Fire", 215),
            useClaude = true,
            search = {
                listOf(
                    song("September (Made Popular By Earth, Wind & Fire) [Vocal Version]", "Party Tyme Karaoke", "3:38"),
                    song("September", "Earth, Wind & Fire", "3:35"),
                )
            },
            askClaude = { _, _, _ -> asked++; SpotifyClaudeMatch.Answer(choice = -1, relation = "another version") },
        )
        assertEquals("Earth, Wind & Fire", outcome.song?.artist)
        assertEquals(0, asked)
    }

    @Test
    fun `a podcast episode is not looked for as a song`() = kotlinx.coroutines.runBlocking {
        val episode = SpotifyTrack(id = "episode:abc", title = "Ep. 12 - Lemon talk", artist = "", album = null, durationMs = 0, imageUrl = null)
        val outcome = SpotifyClaudeMatch.match(
            episode,
            useClaude = true,
            search = { error("searched for an episode") },
            searchVideos = { error("searched videos for an episode") },
            searchAlbumTracks = { _, _ -> error("opened albums for an episode") },
            askClaude = { _, _, _ -> error("asked Claude about an episode") },
        )
        assertNull(outcome.song)
        assertTrue(outcome.settled)
        assertEquals(SpotifyClaudeMatch.MissKind.PODCAST, outcome.miss?.kind)
        assertEquals(0, outcome.claudeCalls)
    }
}
