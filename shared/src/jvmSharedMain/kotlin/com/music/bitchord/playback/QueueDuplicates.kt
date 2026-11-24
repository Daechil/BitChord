package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.ClaudeDiagnostics
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The same recording queued twice under different uploads — its audio, its
 * music video, its lyric video, the voice's channel — found in the queue and
 * offered for removal. The queue's own rules ([QueueBuilder]) and the
 * listener's groups ([SameSongLinks]) find what they can for nothing; Claude
 * is asked about the rest, by name, artist and length only.
 *
 * "The same" is narrow on purpose: another language's version, a cover by
 * someone else, a live take, a remix, an acoustic or sped-up cut are all other
 * songs here, and are never grouped.
 */
object QueueDuplicates {
    /** One song queued more than once: the positions in the list asked about, the one kept first. */
    data class Group(val keep: Int, val remove: List<Int>, val reason: String, val byClaude: Boolean)

    /** Asked about at most this many songs at once. */
    const val MAX_SONGS = 200

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Answer(val groups: List<AnswerGroup> = emptyList())

    @Serializable
    internal data class AnswerGroup(val items: List<Int> = emptyList(), val reason: String = "")

    /**
     * The groups in [songs], positions in that list. [protectedCount] songs at
     * its head — the one playing, and any already played — are never offered
     * for removal: a group holding one keeps it. [useClaude] adds Claude's
     * answer to the rules' when Claude can be asked.
     */
    suspend fun find(songs: List<Song>, protectedCount: Int, useClaude: Boolean): List<Group> {
        val list = songs.take(MAX_SONGS)
        if (list.size < 2) return emptyList()
        val parent = IntArray(list.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        fun join(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val claudePairs = HashSet<Int>()
        val reasons = HashMap<Int, String>()
        // The rules first: free, and what the station already holds to be one.
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (QueueBuilder.isSameRecording(list[i], list[j])) join(i, j)
        }
        if (useClaude && LyricsTranslation.claudeKeyUsable) {
            ask(list)?.forEach { group ->
                val first = group.items.first()
                group.items.drop(1).forEach { join(first, it) }
                group.items.forEach { claudePairs += it }
                if (group.reason.isNotBlank()) reasons[first] = group.reason
            }
        }
        val members = list.indices.groupBy(::root).values.filter { it.size >= 2 }
        return members.map { group ->
            val sorted = group.sorted()
            // The one playing or already played stays; otherwise the first in line.
            val keep = sorted.firstOrNull { it < protectedCount } ?: sorted.first()
            val remove = sorted.filter { it != keep && it >= protectedCount }
            val reason = sorted.firstNotNullOfOrNull { reasons[it] }.orEmpty()
            Group(keep, remove, reason, byClaude = sorted.any { it in claudePairs })
        }.filter { it.remove.isNotEmpty() }
    }

    /** Claude's groups, checked: real positions, each in one group at most, two or more to a group. */
    private suspend fun ask(list: List<Song>): List<AnswerGroup>? = withContext(Dispatchers.IO) {
        val input = buildJsonObject {
            putJsonArray("songs") {
                list.forEachIndexed { i, song ->
                    add(buildJsonObject {
                        put("i", i)
                        put("title", song.title)
                        put("artist", song.artist)
                        song.durationMillis().takeIf { it > 0 }?.let { put("seconds", it / 1000) }
                        if (song.isVideo) put("video", true)
                    })
                }
            }
        }
        val result = LyricsTranslation.askClaude(
            purpose = ClaudeDiagnostics.PURPOSE_QUEUE,
            system = INSTRUCTION,
            model = LyricsTranslation.CLAUDE_HAIKU,
            input = input.toString(),
            schema = SCHEMA,
            effort = "medium",
            maxTokens = 4_000,
        ) { text ->
            val answer = runCatching { json.decodeFromString<Answer>(text) }.getOrNull()
            if (answer == null) {
                LyricsTranslation.Parsed(null, ClaudeFailure.BAD_ANSWER, detail = "groups not readable")
            } else {
                LyricsTranslation.Parsed(answer)
            }
        }
        result.value?.let { checked(it.groups, list.size) }
    }

    internal fun checked(groups: List<AnswerGroup>, size: Int): List<AnswerGroup> {
        val seen = HashSet<Int>()
        return groups.mapNotNull { group ->
            val items = group.items.distinct().filter { it in 0 until size }
            // A position in two groups is an answer that cannot be trusted for either.
            if (items.size < 2 || items.any { it in seen }) return@mapNotNull null
            seen += items
            group.copy(items = items)
        }
    }

    /** For tests: what [checked] makes of positions given as lists. */
    fun checkedPositions(groups: List<List<Int>>, size: Int): List<List<Int>> =
        checked(groups.map { AnswerGroup(it) }, size).map { it.items }

    private val INSTRUCTION = buildString {
        appendLine("You find the same recording queued more than once in a music player's queue.")
        appendLine("Each song has its position \"i\", title, artist, length in seconds and whether it is a video upload.")
        appendLine("Group positions only when they are the same recording uploaded more than once: the audio and its music video, a lyric video, an auto-generated \"Topic\" upload, the singer's or the voice synthesizer's channel, a title written in another script or with an English subtitle, a credit written differently.")
        appendLine("These are NOT the same and must never be grouped: a version in another language (an English or Korean version of a Japanese song), a cover by another singer, a live take, a remix, an acoustic, piano, instrumental, sped-up, slowed, TV-size or extended cut, a different song by the same artist, two songs that share a title.")
        appendLine("A music video is often longer than its audio because of an intro or outro; a very different length otherwise means another cut.")
        appendLine("When you are not sure, do not group. Return no groups when nothing is queued twice.")
        appendLine("\"reason\": a few words in Korean on why the songs are one recording.")
    }

    private val SCHEMA = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groups") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("items") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "integer") }
                        }
                        putJsonObject("reason") { put("type", "string") }
                    }
                    put("required", buildJsonArray { add("items"); add("reason") })
                    put("additionalProperties", false)
                }
            }
        }
        put("required", buildJsonArray { add("groups") })
        put("additionalProperties", false)
    }
}
