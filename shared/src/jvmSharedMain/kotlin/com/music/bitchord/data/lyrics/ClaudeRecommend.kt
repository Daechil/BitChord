package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Songs Claude suggests from a starting point — the song playing, the queue,
 * or what the listener plays most — each with a line on why.
 *
 * Claude names songs; it does not know which exist on YouTube Music, and can
 * name one that doesn't exist at all. So every name is looked up there, and
 * only a song whose title and artist match ([SongMatch]) is kept: what comes
 * back is always playable, and the count found is reported beside the count
 * asked for.
 *
 * What is sent: the starting songs' titles and artists, the ones to leave
 * out, the listener's mood words and the language for the reasons. Nothing
 * else — no lyrics, no account, no listening history unless the listener
 * chose "what I play most" as the starting point.
 */
object ClaudeRecommend {

    const val BASIS_SONG = "song"
    const val BASIS_QUEUE = "queue"
    const val BASIS_HISTORY = "history"
    const val BASIS_PLAYLIST = "playlist"

    const val NOVELTY_FAMILIAR = "familiar"
    const val NOVELTY_MIXED = "mixed"
    const val NOVELTY_NEW = "new"

    /** Always Sonnet: the listener chose quality over the small saving here. */
    val MODEL: String get() = LyricsTranslation.CLAUDE_SONNET

    /** A song by name, as sent and as answered. */
    @Serializable
    data class Named(val title: String, val artist: String)

    @Serializable
    private data class Suggestion(val title: String = "", val artist: String = "", val reason: String = "")

    @Serializable
    private data class Answer(val songs: List<Suggestion> = emptyList())

    /** A suggestion found on YouTube Music. */
    data class Pick(val song: Song, val reason: String)

    sealed interface Outcome {
        /**
         * [asked] is how many Claude named; [picks] the ones found and playable;
         * [searchFailed] how many YouTube Music searches failed outright, as
         * opposed to answering with nothing that matched.
         */
        data class Found(val picks: List<Pick>, val asked: Int, val searchFailed: Int = 0) : Outcome
        /** Not sent, or no usable answer: [failure] says why. */
        data class Failed(val failure: ClaudeFailure?) : Outcome
    }

    /** Whether a request could be sent at all: Claude on and a key that hasn't been refused. */
    val available: Boolean
        get() = LyricsTranslation.claudeEnabled && LyricsTranslation.claudeKeyUsable

    /**
     * Asks for [count] songs like [seeds], none of [avoid], and looks each up.
     * [language] is the reasons' language, as a BCP 47 tag.
     */
    suspend fun recommend(
        basis: String,
        seeds: List<Named>,
        avoid: List<Named>,
        mood: String,
        count: Int,
        novelty: String,
        language: String,
    ): Outcome {
        if (!available) return Outcome.Failed(ClaudeFailure.KEY_REJECTED)
        val input = buildJsonObject {
            put("basis", basis)
            putJsonArray("seeds") { seeds.take(MAX_SEEDS).forEach { addJsonObject { put("title", it.title); put("artist", it.artist) } } }
            putJsonArray("avoid") { avoid.take(MAX_AVOID).forEach { addJsonObject { put("title", it.title); put("artist", it.artist) } } }
            put("mood", mood.trim().take(MAX_MOOD_CHARS))
            put("count", count)
            put("novelty", novelty)
            put("reason_language", language)
        }
        val result = try {
            LyricsTranslation.askClaude(
                purpose = ClaudeDiagnostics.PURPOSE_RECOMMEND,
                system = INSTRUCTION,
                model = MODEL,
                input = input.toString(),
                schema = SCHEMA,
                effort = "low",
                maxTokens = 8_000,
            ) { text ->
                val answer = runCatching { json.decodeFromString<Answer>(text) }.getOrNull()
                when {
                    answer == null -> LyricsTranslation.Parsed<Answer>(null, ClaudeFailure.BAD_ANSWER, detail = "not the expected JSON (${text.length} chars)")
                    answer.songs.isEmpty() -> LyricsTranslation.Parsed<Answer>(null, ClaudeFailure.BAD_ANSWER, detail = "no songs in the answer")
                    else -> LyricsTranslation.Parsed<Answer>(answer, linesGot = answer.songs.size)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Outcome.Failed(ClaudeFailure.ERROR)
        }
        val answer = result.value ?: return Outcome.Failed(result.failure)
        val avoided = avoid.map { key(it.title, it.artist) }.toSet()
        val suggestions = answer.songs
            .map { Suggestion(it.title.trim(), it.artist.trim(), it.reason.trim()) }
            .filter { it.title.isNotEmpty() && key(it.title, it.artist) !in avoided }
            .distinctBy { key(it.title, it.artist) }
            .take(count)
        val started = System.nanoTime()
        val lookups = resolve(suggestions)
        val picks = lookups.mapNotNull { it.pick }.distinctBy { it.song.videoId }
        val searchFailed = lookups.count { it.searchFailed }
        // What the lookup made of the answer, beside the request's own entry:
        // "0 of 20 found" says nothing about whether YouTube Music answered.
        ClaudeDiagnostics.record(
            ClaudeDiagnostics.Entry(
                at = System.currentTimeMillis(),
                purpose = ClaudeDiagnostics.PURPOSE_RECOMMEND,
                model = "YouTube Music",
                failure = if (picks.isEmpty() && suggestions.isNotEmpty()) {
                    if (searchFailed == suggestions.size) ClaudeFailure.NETWORK.name else ClaudeFailure.BAD_ANSWER.name
                } else {
                    null
                },
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                linesAsked = suggestions.size,
                linesGot = picks.size,
                detail = "lookup: found ${picks.size}/${suggestions.size}, search failed $searchFailed",
            ),
        )
        return Outcome.Found(picks, suggestions.size, searchFailed)
    }

    /** One suggestion's lookup: the song found, or whether the search itself failed. */
    private class Lookup(val pick: Pick?, val searchFailed: Boolean)

    /** Each suggestion looked up on YouTube Music, a couple at a time, in the order Claude gave them. */
    private suspend fun resolve(suggestions: List<Suggestion>): List<Lookup> = withContext(Dispatchers.IO) {
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        coroutineScope {
            suggestions.map { suggestion ->
                async {
                    gate.withPermit {
                        try {
                            find(suggestion.title, suggestion.artist, suggestion.reason)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Lookup(null, searchFailed = true)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * The song result that is this title by this artist. Judged first by
     * [TrackMatcher] — the app's own cross-catalogue matcher, which knows a
     * "ピースサイン - Peace Sign" listing from "ピースサイン" and a featured credit
     * from another artist — then by [SongMatch], then by [topHitIsSong] for an
     * artist written in another script. A failed search is tried once more
     * before it counts as one.
     */
    private suspend fun find(title: String, artist: String, reason: String): Lookup {
        val query = "$title $artist".trim()
        var result = YtMusicRepository.search(query, SearchFilter.SONGS)
        if (result.isFailure) {
            delay(RETRY_DELAY_MS)
            result = YtMusicRepository.search(query, SearchFilter.SONGS)
        }
        val rows = result.getOrNull() ?: return Lookup(null, searchFailed = true)
        val songs = rows.asSequence()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .filter { it.videoId.isNotBlank() }
            .take(SEARCH_DEPTH)
            .toList()
        val found = TrackMatcher.best(songs, TrackMatcher.Target(title, artist))
            ?: songs.firstOrNull { song ->
                SongMatch.sameSong(title, artist, 0L, song.title, song.artist, song.durationMillis().takeIf { it > 0 }?.div(1000.0))
            }
            ?: songs.take(TRUSTED_RANKS).firstOrNull { song -> topHitIsSong(title, song.title) }
        return Lookup(found?.let { Pick(it, reason) }, searchFailed = false)
    }

    /**
     * YouTube Music writes artists in the app's language ("아이유", "요네즈 켄시")
     * where Claude may write them otherwise ("IU", "Kenshi Yonezu"), and with no
     * length to go on [SongMatch] can't tell those apart from two artists. A hit
     * near the top of a search for this very title and artist, with the same
     * title and the same version, is taken as the song: a cover rarely ranks
     * that high for its original's name.
     */
    internal fun topHitIsSong(title: String, foundTitle: String): Boolean {
        val wanted = TrackMatcher.parseTitle(title)
        val got = TrackMatcher.parseTitle(foundTitle)
        if (wanted.core.isEmpty() || wanted.versions != got.versions) return false
        if (SongMatch.versionOf(title) != SongMatch.versionOf(foundTitle)) return false
        if (wanted.core == got.core || TrackMatcher.isTranslationMatch(wanted, got)) return true
        // "ピースサイン - Peace Sign": the original and its English name, either
        // side of a dash. One whole side has to be the title asked for.
        return foundTitle.split(" - ", " – ", " — ").size > 1 &&
            foundTitle.split(" - ", " – ", " — ").any { TrackMatcher.parseTitle(it).core == wanted.core }
    }

    private fun key(title: String, artist: String): String =
        (title + "\u0000" + artist).lowercase().filter { it.isLetterOrDigit() || it == '\u0000' }

    private const val MAX_SEEDS = 30
    private const val MAX_AVOID = 120
    const val MAX_MOOD_CHARS = 200
    /** Two at a time: twenty searches in a burst is what YouTube answers with a refusal. */
    private const val LOOKUPS_AT_ONCE = 2
    private const val RETRY_DELAY_MS = 1_500L
    private const val SEARCH_DEPTH = 5
    /** How far down a search a same-titled hit is trusted without its artist; see [topHitIsSong]. */
    private const val TRUSTED_RANKS = 2

    private val json = Json { ignoreUnknownKeys = true }

    private val SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("songs") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("reason") { put("type", "string") }
                        }
                        putJsonArray("required") { add("title"); add("artist"); add("reason") }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { add("songs") }
            put("additionalProperties", false)
        }
    }

    private val INSTRUCTION = """
        You recommend songs for a music player. The input is JSON:
        - "basis": what the seeds are — "song" (the song playing now), "queue" (the songs queued), "history" (the songs this listener plays most), or "playlist" (songs from one playlist: recommend songs that would fit in it).
        - "seeds": songs as {"title","artist"} to recommend from.
        - "avoid": songs not to recommend: already queued, already played a lot, or recommended before.
        - "mood": optional words from the listener about what they want, in any language. Follow them when given.
        - "count": how many songs to return.
        - "novelty": "familiar" = mostly the seeds' own artists and artists very close to them; "mixed" = about half and half; "new" = mostly artists not among the seeds.
        - "reason_language": the language to write each reason in.
        Return "songs": up to "count" items of {"title","artist","reason"}.
        - Only real, officially released songs you are confident exist, as they are titled on streaming services, with the title and the main artist written exactly as the original release credits them (for example 米津玄師, IU, YOASOBI), and the main artist only. Never invent songs. Fewer songs is better than a made-up one.
        - No song from "seeds" or "avoid", and no song twice. Vary the artists: at most two songs by one artist unless "novelty" is "familiar".
        - Prefer the original studio recording; no live, remix, cover or sped-up versions unless the mood asks for them.
        - "reason": one short sentence, under 80 characters, on why it fits — the sound, the mood, a connection to a seed. No quotes from lyrics.
    """.trimIndent()
}
