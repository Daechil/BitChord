package com.music.bitchord.data.lyrics

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A song's background — what it was written for, what it is about, and what
 * its harder phrases mean — looked up once on the web by Claude and kept on
 * the device, so a translation, an explanation and a batch translating ahead
 * can all read the song the way it is meant rather than line by line.
 *
 * Only the title, artist and album are sent, never the lyrics. The search is
 * held to wikis that write about songs (namu.wiki first, which covers
 * Japanese, vocal-synth and Korean songs in Korean), with one search of the
 * whole web after where nothing turned up. Haiku does it whatever model
 * translates: in tests it found more, for a fifth of Sonnet's price.
 *
 * What comes back is a summary, not the lyrics: a phrase longer than a short
 * expression, or a sentence that repeats the lyrics, is dropped here even when
 * the model was told not to quote.
 */
object SongBackgrounds {
    @Serializable
    data class Note(val phrase: String, val meaning: String)

    @Serializable
    data class Background(
        /** False when nothing reliable was found; kept so the song is not searched again for a while. */
        val found: Boolean,
        val background: String = "",
        val notes: List<Note> = emptyList(),
        /** "high", "medium" or "low", as the model judged its sources. */
        val confidence: String = "",
        /** The sites it came from, by name. */
        val sources: List<String> = emptyList(),
        val at: Long = 0,
        val searches: Int = 0,
        /** The language it was written in. */
        val language: String = "",
    )

    /** The song as the player names it: what is searched for. */
    data class SongName(val title: String, val artist: String, val album: String? = null)

    /** Settings' switch; nothing is looked up while it is off. Stored backgrounds are still used. */
    @Volatile
    var enabled: Boolean = false

    /** Search the whole web once more when the wikis had nothing. */
    @Volatile
    var widenWhenMissing: Boolean = true

    const val DIRECTORY = "song_background_v1"
    private const val MISSING_KEPT_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_NOTES = 8
    private const val MAX_PHRASE_CHARS = 12
    private const val WHOLE_LINE_CHARS = 8
    private const val QUOTE_RUN_CHARS = 12
    // Not genius.com: Claude's search refuses it as a domain ("not accessible to
    // our user agent"), and one refused domain fails the whole request.
    private val WIKIS = listOf("namu.wiki", "wikipedia.org", "vocadb.net")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val names = ConcurrentHashMap<String, SongName>()
    private val memory = ConcurrentHashMap<String, Background>()
    private val running = ConcurrentHashMap<String, CompletableDeferred<Background?>>()

    /** The player names the song it is showing lyrics for, so a request about the track can name it too. */
    fun noteSong(trackId: String, title: String, artist: String, album: String? = null) {
        if (trackId.isBlank() || title.isBlank()) return
        if (names.size > 500) names.clear()
        names[trackId] = SongName(title, artist, album?.takeIf { it.isNotBlank() })
    }

    fun nameOf(trackId: String): SongName? = names[trackId]

    /** The song, when its background is to be looked up: the setting on, Claude usable, and nothing kept for it yet. */
    fun toLookUp(trackId: String): SongName? {
        if (!enabled || !LyricsTranslation.claudeKeyUsable) return null
        val name = nameOf(trackId) ?: return null
        return name.takeIf { stored(it.title, it.artist) == null && running[keyOf(it.title, it.artist)] == null }
    }

    /** One key per song whatever the upload: its name, cleaned the way lyric searches clean it. */
    internal fun keyOf(title: String, artist: String): String {
        fun clean(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()
        val raw = clean(title.forLyricsSearch()) + "\u0000" + clean(artist.artistForLyricsSearch())
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun directory(): File? = (LyricsTranslation.keepDir ?: LyricsTranslation.cacheDir)?.let { File(it, DIRECTORY) }

    /** What is stored for the song, if anything still current: a "not found" lasts [MISSING_KEPT_MS]. */
    fun stored(title: String, artist: String, now: Long = System.currentTimeMillis()): Background? {
        val key = keyOf(title, artist)
        val value = memory[key] ?: directory()?.let { File(it, "$key.json") }?.takeIf { it.isFile }
            ?.let { file -> runCatching { json.decodeFromString<Background>(file.readText()) }.getOrNull() }
            ?.also { memory[key] = it }
        return value?.takeIf { usable(it, now) }
    }

    internal fun usable(value: Background, now: Long): Boolean = value.found || now - value.at < MISSING_KEPT_MS

    fun storedFor(trackId: String): Background? = nameOf(trackId)?.let { stored(it.title, it.artist) }?.takeIf { it.found }

    private fun save(title: String, artist: String, value: Background) {
        val key = keyOf(title, artist)
        memory[key] = value
        runCatching {
            val dir = directory() ?: return
            dir.mkdirs()
            val target = File(dir, "$key.json")
            val temporary = File(dir, "$key.json.tmp")
            temporary.writeText(json.encodeToString(Background.serializer(), value))
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    /** How many songs have a background stored, and the bytes they take. */
    fun storedCount(): Pair<Int, Long> {
        val files = directory()?.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clearAll() {
        memory.clear()
        directory()?.listFiles()?.forEach { it.delete() }
    }

    /**
     * The song's background: stored, or looked up now when Settings allows it
     * and Claude can be asked. [lyrics] is only for keeping the lyrics out of
     * what is kept. Null when there is none and none can be had.
     */
    suspend fun ensure(
        song: SongName,
        targetLanguageTag: String,
        lyrics: List<String>,
    ): Background? {
        stored(song.title, song.artist)?.let { return it.takeIf { it.found } }
        if (!enabled || !LyricsTranslation.claudeKeyUsable) return null
        val key = keyOf(song.title, song.artist)
        val mine = CompletableDeferred<Background?>()
        val already = running.putIfAbsent(key, mine)
        if (already != null) return already.await()
        try {
            val target = targetLanguageTag.trim().ifBlank { "en" }
            var found = research(song, target, WIKIS, lyrics)
            if (found?.found == false && widenWhenMissing) {
                found = research(song, target, null, lyrics)?.let { wide ->
                    wide.copy(searches = wide.searches + (found?.searches ?: 0))
                } ?: found
            }
            found?.let { save(song.title, song.artist, it) }
            mine.complete(found?.takeIf { it.found })
            return found?.takeIf { it.found }
        } catch (error: Throwable) {
            mine.complete(null)
            throw error
        } finally {
            running.remove(key, mine)
        }
    }

    private suspend fun research(song: SongName, target: String, domains: List<String>?, lyrics: List<String>): Background? =
        withContext(Dispatchers.IO) {
            val tools = searchTools(domains)
            val input = buildJsonObject {
                put("title", song.title)
                put("artist", song.artist)
                song.album?.let { put("album", it) }
            }
            val result = LyricsTranslation.askClaude(
                purpose = ClaudeDiagnostics.PURPOSE_BACKGROUND,
                system = instruction(target),
                model = LyricsTranslation.CLAUDE_HAIKU,
                input = input.toString(),
                schema = SCHEMA,
                effort = "medium",
                maxTokens = 4_000,
                tools = tools,
            ) { text -> parse(text, target, lyrics)?.let { LyricsTranslation.Parsed(it) } ?: LyricsTranslation.Parsed(null, ClaudeFailure.BAD_ANSWER, detail = "background not readable") }
            result.value
        }

    /** The web search as this research asks it: twice at most, by Claude itself, on [domains] or anywhere. */
    internal fun searchTools(domains: List<String>?): kotlinx.serialization.json.JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "web_search_20260209")
                put("name", "web_search")
                put("max_uses", 2)
                // Searched by Claude itself: the filtering code's calls failed four times in five in tests.
                putJsonArray("allowed_callers") { add("direct") }
                if (domains != null) putJsonArray("allowed_domains") { domains.forEach { add(it) } }
            },
        )
    }

    /** The sites a batch's research may search: one try, no wider second search. */
    internal val batchDomains: List<String> get() = WIKIS

    /** The background's own answer, as one field of a translation batch's answer. */
    internal val batchSchema: JsonObject get() = SCHEMA

    /** The key the background goes under in a translation batch's answer. */
    const val BATCH_FIELD = "song_background"

    /**
     * What a translation batch is told when the song's background is to be
     * looked up in the same request: research first, filed under [BATCH_FIELD],
     * then read only for tone and word choice.
     */
    internal fun batchInstruction(target: String): String = buildString {
        appendLine()
        appendLine("Before you translate, research the song named in \"song\" with the web search. Put what you find in \"$BATCH_FIELD\", following these rules:")
        append(instruction(target))
        appendLine("Then translate. Use what you found only for tone and word choice, as rule 10 says.")
    }

    /**
     * Files the background a translation batch looked up for [song]: the
     * answer's [BATCH_FIELD], kept out of the lyrics as [parse] keeps any
     * other. A song not found is kept as such, so it is not searched again for
     * a month. Nothing when the field is missing or unreadable.
     */
    internal fun fileBatchAnswer(song: SongName, target: String, field: kotlinx.serialization.json.JsonElement?, lyrics: List<String>, searches: Int): Background? {
        val text = (field as? JsonObject)?.toString() ?: return null
        val value = parse(text, target, lyrics)?.copy(searches = searches) ?: return null
        save(song.title, song.artist, value)
        return value
    }

    private fun instruction(target: String): String {
        val language = Locale.forLanguageTag(target.replace('_', '-')).getDisplayName(Locale.ENGLISH).ifBlank { target }
        return buildString {
            appendLine("You research one song for a lyric translator in a music app.")
            appendLine("Search the web for this exact song by its title and artist before you answer.")
            appendLine("Write in $language ($target), whatever language the sources are in: the background and every note's meaning in $language, only the phrases themselves as the song writes them.")
            appendLine("- \"background\": 3 to 5 short sentences: what the song was written for (anime, drama, game, film) if anything, what it is about, and what the artist or official sources said about it. Say which parts are fan interpretation.")
            appendLine("- \"notes\": up to $MAX_NOTES hard expressions in the song, each as a short \"phrase\" of a few words and its \"meaning\". Never copy a whole lyric line.")
            appendLine("  Put first what a translator would miss: wordplay and puns, above all two words read alike (in Japanese, one reading written with two sets of kanji; in Korean or Chinese, one sound with two meanings), double meanings, a title hidden in the words; then slang, archaic or literary words, and references.")
            appendLine("  Look for them in what the song's wiki pages and fan explanations say about its words. Leave out plain words a dictionary explains, and the song's or artist's own name.")
            appendLine("- \"confidence\": \"high\" if official or well-sourced, \"medium\" if from a reliable wiki, \"low\" otherwise.")
            appendLine("- \"sources\": the sites you used, by name.")
            appendLine("Use only what the search results say about this exact song. Never quote the lyrics. Never invent facts, credits or interviews.")
            appendLine("If you cannot find this exact song, set \"found\" to false and leave the other fields empty.")
            appendLine("Before you answer, check that the background and the meanings are written in $language.")
        }
    }

    private val SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("found") { put("type", "boolean") }
                putJsonObject("background") { put("type", "string") }
                putJsonObject("notes") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("phrase") { put("type", "string") }
                            putJsonObject("meaning") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("phrase")
                            add("meaning")
                        }
                        put("additionalProperties", false)
                    }
                }
                putJsonObject("confidence") { put("type", "string") }
                putJsonObject("sources") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") {
                add("found")
                add("background")
                add("notes")
                add("confidence")
                add("sources")
            }
            put("additionalProperties", false)
        }
    }

    @Serializable
    private data class Answer(
        val found: Boolean = false,
        val background: String = "",
        val notes: List<Note> = emptyList(),
        val confidence: String = "",
        val sources: List<String> = emptyList(),
    )

    internal fun parse(text: String, target: String, lyrics: List<String>, now: Long = System.currentTimeMillis()): Background? {
        val answer = runCatching { json.decodeFromString<Answer>(text) }.getOrNull() ?: return null
        val raw = Background(
            found = answer.found && answer.background.isNotBlank(),
            background = answer.background.trim(),
            notes = answer.notes,
            confidence = answer.confidence.trim().lowercase(Locale.ROOT),
            sources = answer.sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6),
            at = now,
            language = target,
        )
        return clean(raw, lyrics)
    }

    /**
     * The lyrics kept out of what is kept: a note whose phrase is longer than
     * an expression, and any sentence of the summary that repeats
     * [QUOTE_RUN_CHARS] characters of a lyric line, are dropped.
     */
    internal fun clean(value: Background, lyrics: List<String>): Background {
        if (!value.found) return value.copy(background = "", notes = emptyList())
        val allLines = lyrics.map(::squash)
        val lines = allLines.filter { it.length >= QUOTE_RUN_CHARS }
        // A whole lyric line given as a "phrase" is a quote, however short the line.
        val wholeLines = allLines.filter { it.length >= WHOLE_LINE_CHARS }.toSet()
        fun quotes(text: String): Boolean {
            val flat = squash(text)
            if (flat.length < QUOTE_RUN_CHARS) return false
            return lines.any { line ->
                (0..line.length - QUOTE_RUN_CHARS).any { start -> flat.contains(line.substring(start, start + QUOTE_RUN_CHARS)) }
            }
        }
        val sentences = value.background.split(Regex("(?<=[.!?。！？])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val kept = sentences.filterNot(::quotes).joinToString(" ")
        val notes = value.notes
            .map { Note(it.phrase.trim(), it.meaning.trim()) }
            .filter { note ->
                val phrase = squash(note.phrase)
                phrase.isNotEmpty() && note.meaning.isNotEmpty() && phrase.length <= MAX_PHRASE_CHARS &&
                    phrase !in wholeLines && !quotes(note.meaning)
            }
            .take(MAX_NOTES)
        return value.copy(found = kept.isNotBlank(), background = kept, notes = notes)
    }

    /** Letters and digits only, lower case: so spacing and punctuation cannot hide a quote. */
    private fun squash(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** The song's name and, where one is kept, its background, for a request's input. */
    internal fun context(trackId: String?): JsonObject? {
        val name = trackId?.let(::nameOf) ?: return null
        val found = stored(name.title, name.artist)?.takeIf { it.found }
        return buildJsonObject {
            put("title", name.title)
            put("artist", name.artist)
            if (found != null) {
                put("background", found.background)
                if (found.notes.isNotEmpty()) {
                    put(
                        "phrase_notes",
                        JsonArray(found.notes.map { note -> buildJsonObject { put("phrase", note.phrase); put("meaning", note.meaning) } }),
                    )
                }
            }
        }
    }
}
