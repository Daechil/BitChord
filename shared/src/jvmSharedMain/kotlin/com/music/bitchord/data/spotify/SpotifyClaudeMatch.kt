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

    /**
     * [settled] is false when the answer could not be had — YouTube turned the
     * searches away, or Claude's call failed — so "not found" here means "try
     * again later", and is never to be remembered as the track's answer.
     */
    class Outcome(
        val song: Song?,
        val how: How,
        val claudeCalls: Int,
        val candidates: Int,
        val settled: Boolean = true,
        /** Why [song] is null, for the grey row to say; null when a song was found. */
        val miss: Miss? = null,
    )

    /** KO: why a track was not matched — shown under its grey row. */
    enum class MissKind { NO_RESULTS, OTHER_VERSION, LENGTH, NOT_THERE, UNREACHABLE }

    /** [seconds]: for [MissKind.LENGTH], how far the closest same recording was from the track's length. */
    data class Miss(val kind: MissKind, val seconds: Int = 0)

    /** Every attempt at a YouTube Music search was turned away (not: it found nothing). */
    class SearchRefused(query: String) : Exception("search refused: ${query.take(40)}")

    /** Music videos offered at most, when no song has the track's length. */
    private const val MAX_VIDEOS = 3

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
        /** YouTube Music's videos for a query — a last look when no song has the track's length. */
        searchVideos: suspend (String) -> List<Song> = ::searchVideoRows,
    ): Outcome {
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
            album = track.album,
        )
        var refused = 0
        suspend fun searched(query: String): List<Song> = runCatching { search(query) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it; refused++ }
            .getOrDefault(emptyList())
        val first = searched(listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" "))
        val ruled = TrackMatcher.best(first, target)
        // The rules' pick stands only when it is plainly the track: its performer, its version.
        // A cover by someone else or the original of a "Twin Ver." got past them on a title alone.
        if (ruled != null && plainlySame(track, ruled)) return Outcome(ruled, How.RULES, 0, first.size)
        fun unfound() = Miss(if (refused > 0) MissKind.UNREACHABLE else MissKind.NO_RESULTS)
        if (!useClaude) {
            val song = ruled ?: first.firstOrNull()
            return Outcome(song, How.NONE, 0, first.size, settled = refused == 0, miss = if (song == null) unfound() else null)
        }

        val titleOnly = searched(plainTitle(track.title))
        var candidates = merged(first, titleOnly)
        // The performer's songs of the track's length, if they had to be searched for (below).
        var performerClose: List<Song> = emptyList()
        // Nothing of the track's length among them: the performer's own songs, searched by name
        // alone — Spotify's English title ("Karma") may never bring up the original ("염라").
        // Those of the right length go first, so the 12 shown to Claude keep them.
        if (track.durationMs > 0 && candidates.none { closeInLength(it, track) }) {
            val performer = track.artist.split(",").first().trim()
            if (performer.isNotBlank()) {
                val byPerformer = searched(performer)
                performerClose = byPerformer.filter { closeInLength(it, track) }
                candidates = merged(performerClose, candidates + byPerformer)
            }
        }
        // Still nothing of that length: some songs are on YouTube Music only as a music video
        // (common for Vocaloid producers). Videos of the right length join, first in line;
        // Claude takes one only as the performer's own video, and only when no song matches.
        if (track.durationMs > 0 && candidates.none { closeInLength(it, track) }) {
            val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
            val videos = runCatching { searchVideos(query) }.getOrDefault(emptyList())
                .filter { closeInLength(it, track) }.take(MAX_VIDEOS)
            if (videos.isNotEmpty()) candidates = (videos + candidates).distinctBy { it.videoId }.take(MAX_CANDIDATES)
        }
        // Nothing to choose from: no call. Searches turned away are worth trying again;
        // two that answered with nothing are the answer.
        if (candidates.isEmpty()) return Outcome(null, How.NONE, 0, 0, settled = refused == 0, miss = unfound())

        // Claude could not answer (a limit reached, resting after a rate limit, the key refused):
        // the rules' own pick rather than nothing, so the song still plays — not remembered,
        // and asked again next time the playlist opens.
        fun fallback(calls: Int): Outcome {
            // Searched by the performer's name, a song of the right length is theirs as surely as
            // the title search's first row of that length is the title's.
            val song = ruled ?: first.firstOrNull { closeInLength(it, track) } ?: performerClose.firstOrNull()
            return Outcome(
                song, How.RULES, calls, candidates.size, settled = false,
                miss = if (song == null) Miss(MissKind.UNREACHABLE) else null,
            )
        }
        val answer = ask(track, candidates) ?: return fallback(1)
        accepted(answer, candidates, track)?.let { return Outcome(it, How.CLAUDE, 1, candidates.size) }
        val firstMiss = whyNot(answer, candidates, track)

        // None among these: Claude's own searches, once.
        val refusedBefore = refused
        val more = answer.searches.take(2).flatMap { query -> searched(query) }
        if (more.isEmpty()) return Outcome(null, How.NONE, 1, candidates.size, settled = refused == refusedBefore, miss = firstMiss)
        candidates = merged(candidates, more)
        val second = ask(track, candidates, askForSearches = false) ?: return fallback(2)
        val chosen = accepted(second, candidates, track)
        // The more telling of the two answers: "only other versions" beats a later "none".
        val miss = if (chosen != null) null else whyNot(second, candidates, track).takeUnless {
            it.kind == MissKind.NOT_THERE && firstMiss.kind != MissKind.NOT_THERE
        } ?: firstMiss
        return Outcome(chosen, if (chosen != null) How.CLAUDE_SECOND_SEARCH else How.NONE, 2, candidates.size, miss = miss)
    }

    /**
     * KO: the YouTube Music songs a grey row's track could be — from its title and
     * artist and from its title alone — for the listener to pick from by hand.
     */
    suspend fun candidates(track: SpotifyTrack): List<Song> {
        suspend fun safe(query: String) = runCatching { searchSongs(query) }.getOrDefault(emptyList())
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val first = safe(query)
        // The songs, then a few music videos — some songs are on YouTube Music only as one.
        val videos = runCatching { searchVideoRows(query) }.getOrDefault(emptyList()).take(MAX_VIDEOS)
        return (merged(first, safe(plainTitle(track.title))) + videos).distinctBy { it.videoId }
    }

    /** YouTube Music's videos for [query], anonymous then signed in, as [searchSongs]. */
    private suspend fun searchVideoRows(query: String): List<Song> {
        val anonymous = YtMusicRepository.search(searchable(query), SearchFilter.VIDEOS)
        anonymous.getOrNull()?.songs()?.filter { it.isVideo }?.takeIf { it.isNotEmpty() }?.let { return it }
        return YtMusicRepository.searchPage(searchable(query), SearchFilter.VIDEOS, anonymous = false)
            .getOrNull()?.rows?.songs()?.filter { it.isVideo }.orEmpty()
    }

    /** Why Claude's [answer] gave no song: another version, the length, or nothing there. */
    internal fun whyNot(answer: Answer, candidates: List<Song>, track: SpotifyTrack): Miss {
        val song = candidates.getOrNull(answer.choice)
        return when {
            answer.relation == OTHER_VERSION -> Miss(MissKind.OTHER_VERSION)
            (answer.relation == SAME_RECORDING || answer.relation == SAME_SONG_OTHER_UPLOAD) && song != null -> {
                val expected = track.durationMs.takeIf { it > 0 }?.div(1000)
                val actual = song.durationMillis().takeIf { it > 0 }?.div(1000)
                if (expected != null && actual != null) Miss(MissKind.LENGTH, abs(expected - actual).toInt()) else Miss(MissKind.NOT_THERE)
            }
            else -> Miss(MissKind.NOT_THERE)
        }
    }

    /**
     * YouTube Music's songs for [query]. A search that fails — a playlist's
     * songs are searched several at a time, and YouTube turns some away — is
     * tried once more after a pause: a failed search used to leave the song
     * marked as not on YouTube Music, and unplayable.
     */
    private suspend fun searchSongs(query: String): List<Song> {
        repeat(2) { attempt ->
            // Anonymous first, as before; then signed in, as the search tab asks. A phone that
            // had looked up a long playlist got empty anonymous answers back for hours, while
            // the signed-in search went on working — the whole playlist stayed grey.
            val anonymous = YtMusicRepository.search(searchable(query), SearchFilter.SONGS)
            anonymous.getOrNull()?.songs()?.takeIf { it.isNotEmpty() }?.let { return it }
            val signedIn = YtMusicRepository.searchPage(searchable(query), SearchFilter.SONGS, anonymous = false)
            signedIn.getOrNull()?.rows?.songs()?.let { found ->
                if (found.isNotEmpty() || anonymous.isSuccess) return found
            }
            if (attempt == 0) kotlinx.coroutines.delay(SEARCH_RETRY_MS)
        }
        throw SearchRefused(query)
    }

    /** The songs among search rows, the top result's card included. */
    private fun List<SearchResult>.songs(): List<Song> = mapNotNull {
        when (it) {
            is SearchResult.Track -> it.song
            is SearchResult.TopTrack -> it.song
            else -> null
        }
    }.distinctBy { it.videoId }

    private const val SEARCH_RETRY_MS = 1_500L

    /** Words a version is named by; a title with one the candidate lacks is another version. */
    private val VERSION_WORDS = Regex(
        "\\b(remix|mix|live|acoustic|instrumental|inst|karaoke|sped|slowed|tv|edit|demo|english|japanese|korean|chinese|twin|orchestra|piano|cover)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Spotify credits "A, B"; YouTube Music "A & B" or "A, B": a shared name is enough. */
    private fun names(artist: String): List<String> = artist.split(",", "&", " x ", " X ", " feat", " with ")
        .map { it.lowercase().filter(Char::isLetterOrDigit) }
        .filter { it.length >= 2 }

    /** Within [FALLBACK_LENGTH_GAP_SEC] of the track's length; false when either is unknown. */
    private fun closeInLength(song: Song, track: SpotifyTrack): Boolean {
        val expected = track.durationMs.takeIf { it > 0 }?.div(1000) ?: return false
        val actual = song.durationMillis().takeIf { it > 0 }?.div(1000) ?: return false
        return abs(expected - actual) <= FALLBACK_LENGTH_GAP_SEC
    }

    private const val FALLBACK_LENGTH_GAP_SEC = 3

    /** Same performer (a shared name, however spaced or cased) and no version word the candidate lacks. */
    internal fun plainlySame(track: SpotifyTrack, song: Song): Boolean {
        val wanted = names(track.artist)
        val got = names(song.artist)
        if (wanted.none { w -> got.any { g -> g.contains(w) || w.contains(g) } }) return false
        return VERSION_WORDS.findAll(track.title).map { it.value.lowercase() }
            .none { word -> !song.title.lowercase().contains(word) }
    }

    /** The title without what Spotify adds to it: " - Remastered 2011", "(feat. …)". */
    /**
     * [query] as YouTube's search box should get it: a word with a "-" in front means "leave out
     * results with this word" there, so Spotify's "ヒバナ -Reloaded-" searched for everything but
     * the Reloaded version. The dash is dropped; the word stays.
     */
    internal fun searchable(query: String): String =
        query.replace(Regex("(^|\\s)-+(?=\\S)"), "$1").replace(Regex("\\s+"), " ").trim()

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
                        if (song.isVideo) put("music_video", true)
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
        - Spotify often shows Korean, Japanese and Chinese performers under a romanised or English name (Dareharu for 달의하루, BOL4 for 볼빨간사춘기) and songs under an English title that is not a translation of the original one (Galaxy for 우주를 줄게). When the performer is the same and the lengths agree within 3 seconds, it is the same recording even though the titles share no word. If several of that performer's results have the length, the album decides; if nothing decides, choose -1.
        - A result with "music_video": true is a video, not a song. Choose it only when no song result is the recording, it is the performer's own video (their name or channel), and the lengths agree within 3 seconds; its relation is "$SAME_SONG_OTHER_UPLOAD".
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
