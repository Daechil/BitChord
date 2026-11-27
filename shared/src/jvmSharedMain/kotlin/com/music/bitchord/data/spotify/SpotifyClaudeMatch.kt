package com.music.bitchord.data.spotify

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.ClaudeDiagnostics
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/**
 * KO: a Spotify track's YouTube Music version, with Claude's help where the
 * rules have none.
 *
 * [SpotifyImporter.matchTrack] takes the rules' best candidate and otherwise
 * the search's first row, which is often another song, and finds nothing at
 * all when the search comes back empty — a title Spotify lists in English or
 * romaji while YouTube Music has it in its own script, an extra "- Remastered".
 * Here the rules still go first and cost nothing; only when they find no match
 * does Claude (Haiku) look at the candidates — from the title and artist, and
 * from the title alone — and either pick the same recording or say none and
 * suggest searches of its own, which are tried once.
 *
 * Claude only ever chooses among real search results, and its choice is held
 * to the track's length, so it cannot invent a song or settle on a cover.
 */
object SpotifyClaudeMatch {

    /** How a match was made, for logs and the probe. */
    enum class How { RULES, CLAUDE, CLAUDE_SECOND_SEARCH, NONE }

    class Outcome(val song: Song?, val how: How, val claudeCalls: Int, val candidates: Int)

    /** Candidates shown to Claude at most. */
    private const val MAX_CANDIDATES = 12

    /** How far a chosen candidate's length may be from the track's, in seconds. */
    private const val MAX_LENGTH_GAP_SEC = 12

    private const val SAME_RECORDING = "same recording"
    private const val SAME_SONG_OTHER_UPLOAD = "same recording, another upload"
    private const val OTHER_VERSION = "another version"
    private const val NONE = "none"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal class Answer(
        val choice: Int = -1,
        val relation: String = NONE,
        val searches: List<String> = emptyList(),
    )

    suspend fun match(
        track: SpotifyTrack,
        useClaude: Boolean = LyricsTranslation.claudeKeyUsable,
        search: suspend (String) -> List<Song> = ::searchSongs,
    ): Outcome {
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
            album = track.album,
        )
        val first = runCatching { search(listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")) }
            .getOrDefault(emptyList())
        val ruled = TrackMatcher.best(first, target)
        // The rules' pick stands only when it is plainly the track: its performer, its version.
        // A cover by someone else or the original of a "Twin Ver." got past them on a title alone.
        if (ruled != null && plainlySame(track, ruled)) return Outcome(ruled, How.RULES, 0, first.size)
        if (!useClaude) return Outcome(ruled ?: first.firstOrNull(), How.NONE, 0, first.size)

        val titleOnly = runCatching { search(plainTitle(track.title)) }.getOrDefault(emptyList())
        var candidates = merged(first, titleOnly)

        val answer = ask(track, candidates) ?: return Outcome(null, How.NONE, 1, candidates.size)
        accepted(answer, candidates, track)?.let { return Outcome(it, How.CLAUDE, 1, candidates.size) }

        // None among these: Claude's own searches, once.
        val more = answer.searches.take(2).flatMap { query -> runCatching { search(query) }.getOrDefault(emptyList()) }
        if (more.isEmpty()) return Outcome(null, How.NONE, 1, candidates.size)
        candidates = merged(candidates, more)
        val second = ask(track, candidates, askForSearches = false) ?: return Outcome(null, How.NONE, 2, candidates.size)
        val chosen = accepted(second, candidates, track)
        return Outcome(chosen, if (chosen != null) How.CLAUDE_SECOND_SEARCH else How.NONE, 2, candidates.size)
    }

    private suspend fun searchSongs(query: String): List<Song> =
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()

    /** Words a version is named by; a title with one the candidate lacks is another version. */
    private val VERSION_WORDS = Regex(
        "\\b(remix|mix|live|acoustic|instrumental|inst|karaoke|sped|slowed|tv|edit|demo|english|japanese|korean|chinese|twin|orchestra|piano|cover)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Spotify credits "A, B"; YouTube Music "A & B" or "A, B": a shared name is enough. */
    private fun names(artist: String): List<String> = artist.split(",", "&", " x ", " X ", " feat", " with ")
        .map { it.lowercase().filter(Char::isLetterOrDigit) }
        .filter { it.length >= 2 }

    /** Same performer (a shared name, however spaced or cased) and no version word the candidate lacks. */
    internal fun plainlySame(track: SpotifyTrack, song: Song): Boolean {
        val wanted = names(track.artist)
        val got = names(song.artist)
        if (wanted.none { w -> got.any { g -> g.contains(w) || w.contains(g) } }) return false
        return VERSION_WORDS.findAll(track.title).map { it.value.lowercase() }
            .none { word -> !song.title.lowercase().contains(word) }
    }

    /** The title without what Spotify adds to it: " - Remastered 2011", "(feat. …)". */
    internal fun plainTitle(title: String): String =
        title.substringBefore(" - ").replace(Regex("""\s*[(\[](feat|ft|with)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { title }

    private fun merged(a: List<Song>, b: List<Song>): List<Song> =
        (a + b).distinctBy { it.videoId }.filterNot { it.isVideo }.take(MAX_CANDIDATES)

    /** Claude's choice, if it is one: a real candidate, the same recording, and the right length. */
    internal fun accepted(answer: Answer, candidates: List<Song>, track: SpotifyTrack): Song? {
        if (answer.relation != SAME_RECORDING && answer.relation != SAME_SONG_OTHER_UPLOAD) return null
        val song = candidates.getOrNull(answer.choice) ?: return null
        val expected = track.durationMs.takeIf { it > 0 }?.div(1000)
        val actual = song.durationMillis().takeIf { it > 0 }?.div(1000)
        if (expected != null && actual != null && abs(expected - actual) > MAX_LENGTH_GAP_SEC) return null
        return song
    }

    private suspend fun ask(track: SpotifyTrack, candidates: List<Song>, askForSearches: Boolean = true): Answer? {
        val input = buildJsonObject {
            putJsonObject("spotify_track") {
                put("title", track.title)
                put("artist", track.artist)
                track.album?.let { put("album", it) }
                track.durationMs.takeIf { it > 0 }?.let { put("seconds", it / 1000) }
            }
            putJsonArray("youtube_music_results") {
                candidates.forEachIndexed { i, song ->
                    add(buildJsonObject {
                        put("i", i)
                        put("title", song.title)
                        put("artist", song.artist)
                        song.albumName?.let { put("album", it) }
                        song.durationMillis().takeIf { it > 0 }?.let { put("seconds", it / 1000) }
                    })
                }
            }
            put("may_suggest_searches", askForSearches)
        }
        val result = LyricsTranslation.askClaude(
            purpose = ClaudeDiagnostics.PURPOSE_SPOTIFY,
            system = INSTRUCTION,
            model = LyricsTranslation.CLAUDE_HAIKU,
            input = input.toString(),
            schema = SCHEMA,
            effort = "low",
            maxTokens = 2_000,
            cache = true,
        ) { text ->
            val answer = runCatching { json.decodeFromString<Answer>(text) }.getOrNull()
            if (answer == null) LyricsTranslation.Parsed(null, ClaudeFailure.BAD_ANSWER, detail = "answer not readable")
            else LyricsTranslation.Parsed(answer)
        }
        return result.value
    }

    private val INSTRUCTION = """
        You match a Spotify track to its YouTube Music version for a music player that is about to play it.

        The input gives the Spotify track (title, artist, album, length in seconds) and the YouTube Music search results, numbered by "i".

        Choose the result that is the same recording as the Spotify track:
        - The same song by the same performer, the same version. Titles and artist names may be written differently: in another script (アイドル / Idol / 아이돌), romanised (夜に駆ける / Yoru ni Kakeru), translated (밤편지 / Through the Night), with or without "feat." credits, a remaster note or an "Official" tag.
        - Lengths usually agree within a few seconds.
        - Not a cover by someone else, a live recording, a remix, an acoustic, instrumental, karaoke, sped-up or TV-size version, nor another language's version of the song, unless the Spotify track is that version.

        Answer:
        - "choice": the "i" of the matching result, or -1 when none of them is the same recording.
        - "relation": "$SAME_RECORDING" for that release itself, "$SAME_SONG_OTHER_UPLOAD" for the same audio released elsewhere (a single and its album, a compilation), "$OTHER_VERSION" when the closest result is another version, "$NONE" when no result is the song. Choose -1 for anything but the first two.
        - "searches": only when "may_suggest_searches" is true and you chose -1: up to two YouTube Music searches likely to find the recording, such as the title in its original script with the performer's name as YouTube Music writes it. Otherwise an empty list.
        Never choose a result just because it is the first or the closest; -1 is the right answer when the song is not there.
    """.trimIndent()

    private val SCHEMA = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("choice") { put("type", "integer") }
            putJsonObject("relation") {
                put("type", "string")
                putJsonArray("enum") { add(SAME_RECORDING); add(SAME_SONG_OTHER_UPLOAD); add(OTHER_VERSION); add(NONE) }
            }
            putJsonObject("searches") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", buildJsonArray { add("choice"); add("relation"); add("searches") })
        put("additionalProperties", false)
    }
}
