package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        /** [asked] is how many Claude named; [picks] the ones found and playable. */
        data class Found(val picks: List<Pick>, val asked: Int) : Outcome
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
        val picks = resolve(suggestions)
            .distinctBy { it.song.videoId }
        return Outcome.Found(picks, suggestions.size)
    }

    /** Each suggestion looked up on YouTube Music, a few at a time, in the order Claude gave them. */
    private suspend fun resolve(suggestions: List<Suggestion>): List<Pick> = withContext(Dispatchers.IO) {
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        coroutineScope {
            suggestions.map { suggestion ->
                async {
                    gate.withPermit {
                        val song = try {
                            find(suggestion.title, suggestion.artist)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        song?.let { Pick(it, suggestion.reason) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** The first song result that is this title by this artist; null when YouTube Music has none. */
    private suspend fun find(title: String, artist: String): Song? {
        val rows = YtMusicRepository.search("$title $artist".trim(), SearchFilter.SONGS).getOrNull().orEmpty()
        return rows.asSequence()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .take(SEARCH_DEPTH)
            .firstOrNull { song ->
                song.videoId.isNotBlank() &&
                    SongMatch.sameSong(title, artist, 0L, song.title, song.artist, song.durationMillis().takeIf { it > 0 }?.div(1000.0))
            }
    }

    private fun key(title: String, artist: String): String =
        (title + "\u0000" + artist).lowercase().filter { it.isLetterOrDigit() || it == '\u0000' }

    private const val MAX_SEEDS = 30
    private const val MAX_AVOID = 120
    const val MAX_MOOD_CHARS = 200
    private const val LOOKUPS_AT_ONCE = 4
    private const val SEARCH_DEPTH = 5

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
        - "basis": what the seeds are — "song" (the song playing now), "queue" (the songs queued), or "history" (the songs this listener plays most).
        - "seeds": songs as {"title","artist"} to recommend from.
        - "avoid": songs not to recommend: already queued, already played a lot, or recommended before.
        - "mood": optional words from the listener about what they want, in any language. Follow them when given.
        - "count": how many songs to return.
        - "novelty": "familiar" = mostly the seeds' own artists and artists very close to them; "mixed" = about half and half; "new" = mostly artists not among the seeds.
        - "reason_language": the language to write each reason in.
        Return "songs": up to "count" items of {"title","artist","reason"}.
        - Only real, officially released songs you are confident exist, as they are titled on streaming services, with the title in its original script (日本語, 한국어, English…) and the main artist only. Never invent songs. Fewer songs is better than a made-up one.
        - No song from "seeds" or "avoid", and no song twice. Vary the artists: at most two songs by one artist unless "novelty" is "familiar".
        - Prefer the original studio recording; no live, remix, cover or sped-up versions unless the mood asks for them.
        - "reason": one short sentence, under 80 characters, on why it fits — the sound, the mood, a connection to a seed. No quotes from lyrics.
    """.trimIndent()
}
