package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Lightweight, on-demand lyric translation.
 *
 * Translation models are deliberately not installed on the device. A model for
 * every app language would cost tens of megabytes each; translated lyric text
 * is normally only a few kilobytes. Requests are batched and the compact result
 * is kept in a bounded cache under [Context.getCacheDir], so Android may reclaim
 * it and the feature can never grow without limit.
 */
object LyricsTranslation {
    /** Which service wrote a translation, so the player can name it. */
    enum class Engine { CLAUDE, GOOGLE }

    sealed interface Result {
        data class Translated(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
            val engine: Engine = Engine.GOOGLE,
            /** Claude was asked first and gave nothing usable, so this is Google's answer instead. */
            val claudeFellBack: Boolean = false,
            /** Why Claude gave nothing usable, when [claudeFellBack]. */
            val claudeFailure: ClaudeFailure? = null,
        ) : Result

        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult

        data object AlreadyRomanized : RomanizationResult
        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"
    // Version 3 invalidates answers cached before the Latin-script retry existed;
    // otherwise a song that once came back untranslated would never take the
    // repaired path after the app is updated.
    private const val CACHE_VERSION = 3
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    // Room for roughly two thousand songs: every Claude answer kept here is a
    // paid request (and a few seconds) the next play of that song does not spend.
    private const val MAX_CACHE_BYTES = 30L * 1024L * 1024L
    private const val ENGINE_CLAUDE = "claude"
    private const val ENGINE_GOOGLE = "google"
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    private val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = object {
        private val entries = object : LinkedHashMap<String, CachedTranslation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedTranslation>) = size > 12
        }

        @Synchronized fun get(key: String): CachedTranslation? = entries[key]

        @Synchronized fun put(key: String, value: CachedTranslation) {
            entries[key] = value
        }
    }

    /**
     * Where translated lyrics are kept between runs — the phone's cache
     * directory, the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    /**
     * Where answers worth keeping for good live — the phone's files directory.
     * Holds the song explanations. Null falls back to [cacheDir].
     */
    @Volatile
    var keepDir: File? = null
    private const val MEANING_DIRECTORY = "song_meaning_v1"
    private const val MAX_MEANING_BYTES = 4L * 1024L * 1024L
    private const val MAX_MEANING_LYRIC_CHARS = 6_000
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
        /** [ENGINE_CLAUDE] or [ENGINE_GOOGLE]; entries from before this existed are Google's. */
        val engine: String = ENGINE_GOOGLE,
    )

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        /** Ask Claude again even though an answer is cached — for a translation that read badly. */
        fresh: Boolean = false,
    ): Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable

        // Claude where it is set up and answers in full; whatever goes wrong
        // there, the lyric sheet must not, so anything short of a complete
        // answer falls through to Google Translate below — with the reason
        // kept, for the status line and the diagnostics log.
        val claudeTried = claudeEnabled && cleanClaudeKey().isNotEmpty()
        var claudeFailure: ClaudeFailure? = null
        if (claudeTried) {
            val attempt = try {
                claudeEntry(trackId, slots, target, fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ClaudeDiagnostics.record(
                    ClaudeDiagnostics.Entry(
                        at = System.currentTimeMillis(),
                        purpose = ClaudeDiagnostics.PURPOSE_TRANSLATE,
                        model = claudeModel,
                        failure = ClaudeFailure.ERROR.name,
                        linesAsked = slots.size,
                        detail = error.toString(),
                    ),
                )
                ClaudeAttempt(null, fromCache = false, failure = ClaudeFailure.ERROR)
            }
            claudeFailure = attempt.failure
            val entry = attempt.entry
            if (entry != null) {
                val fromCache = attempt.fromCache
                return if (sameLanguage(entry.sourceLanguage, target)) {
                    Result.SameLanguage(entry.sourceLanguage)
                } else {
                    Result.Translated(
                        lines = rebuild(lines, slots, entry.texts),
                        sourceLanguage = entry.sourceLanguage,
                        fromCache = fromCache,
                        engine = Engine.CLAUDE,
                    )
                }
            }
        }

        val cacheKey = cacheKey(trackId, target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    lines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                    claudeFellBack = claudeTried,
                    claudeFailure = claudeFailure,
                )
            }
        }

        val batches = batches(slots)
        val answers = coroutineScope {
            // Two short requests at a time keeps a long lyric fast without
            // competing with playback for every connection in the pool.
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return Result.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        if (source.isBlank()) return Result.Unavailable

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return Result.Unavailable

        // Google's ordinary auto-detection understands many Latin-script
        // Hindi/Urdu/Punjabi lyrics, but its NMT occasionally returns whole
        // phrases unchanged ("tera hone laga hoon" is a common example). If a
        // sizeable part of a Latin-script source survived untouched, use
        // Google's Input Tools to restore the detected language's native script
        // and translate that. Keep the first answer unless the retry actually
        // transforms more of the song, so names and genuinely bilingual lyrics
        // do not get worse merely because they contain Latin text.
        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }
        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
            engine = ENGINE_GOOGLE,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)

        return if (sameLanguage(source, target)) {
            Result.SameLanguage(source)
        } else {
            Result.Translated(
                lines = rebuild(lines, slots, translated),
                sourceLanguage = source,
                fromCache = false,
                claudeFellBack = claudeTried,
                claudeFailure = claudeFailure,
            )
        }
    }

    suspend fun romanize(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        // Romanization always ends in Latin script. The translation destination
        // is still sent as `tl` because the web endpoint requires it, but it is
        // deliberately excluded from this cache key: changing "translate to"
        // cannot change how the source language is pronounced.
        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                lines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val target = targetLanguageTag.trim().ifBlank { "en" }
        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return RomanizationResult.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) return RomanizationResult.Unavailable
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            // Non-Latin input reached this point, so an unchanged response is
            // an unsupported/failed romanization rather than an already-Latin
            // lyric. The fast all-Latin check above handles the real no-op case.
            return RomanizationResult.Unavailable
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = "Latn",
            texts = romanized,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return RomanizationResult.Romanized(
            lines = rebuild(lines, slots, romanized),
            sourceLanguage = source,
            fromCache = false,
        )
    }

    // ---- Claude (Anthropic Messages API) -------------------------------------

    /**
     * Translate with Anthropic's Claude instead of Google Translate. Claude
     * reads the whole song at once, so it keeps one voice and speech level
     * across every line and renders lyrics rather than word-for-word text. Set
     * from the app's settings; when off, or when the key is blank, or when
     * Claude fails for any reason, translation quietly uses Google Translate.
     */
    @Volatile
    var claudeEnabled: Boolean = false

    /** The Claude API key from the Anthropic Console, set by the app's settings. Never committed. */
    @Volatile
    var claudeApiKey: String = ""
        set(value) {
            field = value
            claudeKeyRejected.value = false
        }

    /** True once Claude refused the current key (or its account has no credit), so Settings can say why. */
    val claudeKeyRejected = MutableStateFlow(false)

    /** Which Claude answers: [CLAUDE_HAIKU] by default, [CLAUDE_SONNET] for a better read at a higher price. */
    @Volatile
    var claudeModel: String = CLAUDE_HAIKU

    /** Korean speech level the translation is written in: [SPEECH_AUTO], [SPEECH_CASUAL] or [SPEECH_POLITE]. */
    @Volatile
    var translationSpeech: String = SPEECH_AUTO

    /** How close to the words: [MODE_NATURAL] (idiomatic) or [MODE_LITERAL] (close to the original). */
    @Volatile
    var translationMode: String = MODE_NATURAL

    /** Told what every Claude answer cost in tokens, so the app can keep a tally. */
    @Volatile
    var usageSink: ((model: String, inputTokens: Long, outputTokens: Long) -> Unit)? = null

    const val CLAUDE_HAIKU = "claude-haiku-5-5"
    const val CLAUDE_SONNET = "claude-sonnet-5-5"
    const val SPEECH_AUTO = "auto"
    const val SPEECH_CASUAL = "casual"
    const val SPEECH_POLITE = "polite"
    const val MODE_NATURAL = "natural"
    const val MODE_LITERAL = "literal"

    private const val CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_API_VERSION = "2026-10-21"
    // Haiku by default: a song's worth of lines is a small, well-specified job,
    // and at Haiku's price a whole library translates for what a few songs
    // would cost on a larger model. Neither Haiku nor Sonnet is sent a
    // server-side refusal fallback — a request the safety classifiers decline
    // simply comes back as a refusal, which [claudeText] turns into null and
    // the caller into Google Translate.
    private const val CLAUDE_COOLDOWN_MS = 60L * 1000L
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** A key Claude refused (401/403, or no credit left). Not retried until the key changes. */
    @Volatile
    private var rejectedClaudeKey: String? = null

    /** After a rate limit or an overloaded answer, Claude rests until then. */
    @Volatile
    private var claudeCooldownUntil = 0L

    private val claudeClient by lazy {
        Http.client.newBuilder()
            // Claude thinks before it answers; a whole song can take most of a
            // minute, and Google Translate still answers if this runs out.
            .callTimeout(90, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The key as it is sent: only the characters an API key can hold. A pasted
     * key often carries a space or an invisible character, and OkHttp throws
     * rather than send a header containing one.
     */
    private fun cleanClaudeKey(): String =
        claudeApiKey.filter { it in '!'..'~' }

    /** True while a Claude key is set that Claude has not refused. */
    val claudeKeyUsable: Boolean
        get() {
            val key = cleanClaudeKey()
            return key.isNotEmpty() && key != rejectedClaudeKey
        }

    /**
     * Claude's answer for the whole song, cached separately from Google's so
     * switching engines never shows the other engine's text. Null whenever the
     * answer can't be used as-is, which sends the caller back to Google.
     */
    private suspend fun claudeEntry(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
        fresh: Boolean,
    ): ClaudeAttempt {
        // The style is part of the key: a song read in 반말 and the same song
        // read in 존댓말 are two answers, and switching between them must not
        // show the other one.
        val cacheKey = cacheKey("claude\u0000$translationSpeech\u0000$translationMode\u0000$trackId", target, slots)
        val cached = if (fresh) null else memory.get(cacheKey) ?: readCache(cacheKey)?.also { memory.put(cacheKey, it) }
        // An answer kept before refusals were caught may be one sentence of
        // refusal under every line; it is asked again instead of shown.
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size &&
            repeatedAcrossLines(cached.texts, slots.map { it.text }) == null
        ) {
            return ClaudeAttempt(cached.copy(engine = ENGINE_CLAUDE), fromCache = true, failure = null)
        }
        val reply = requestClaudeTranslation(slots, target)
        val fresh = reply.value ?: return ClaudeAttempt(null, fromCache = false, failure = reply.failure ?: ClaudeFailure.ERROR)
        val entry = CachedTranslation(
            sourceLanguage = fresh.sourceLanguage,
            targetLanguage = target,
            // A line left empty (often a bare "oh" or "yeah") keeps the
            // original rather than showing a blank translation under it.
            texts = fresh.translations.mapIndexed { index, text -> text.ifBlank { slots[index].text } },
            engine = ENGINE_CLAUDE,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return ClaudeAttempt(entry, fromCache = false, failure = null)
    }

    /** What [claudeEntry] came back with: the entry, or why there is none. */
    private class ClaudeAttempt(
        val entry: CachedTranslation?,
        val fromCache: Boolean,
        val failure: ClaudeFailure?,
    )

    private fun translationInstruction(target: String, count: Int, keyed: Boolean): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = canonicalLanguage(target) == "ko"
        return buildString {
            appendLine("You are a professional lyric translator localising song lyrics for a music app.")
            appendLine("Translate every input line into $languageName ($target).")
            appendLine()
            appendLine("Context: the listener is playing this song in the app right now, and its original lyrics are already on their screen, synced to the music. Your translation is shown under each original line, like a subtitle, so they can follow what they are hearing. It is not published or shared anywhere. This is the app's lyric translation feature, so translate every line as the song expresses it, including lines about violence, death, crime, sex or other dark themes.")
            appendLine()
            appendLine("Rules:")
            if (keyed) {
                appendLine("1. \"translations\" is an object with one key per input line: the line's index \"i\" (\"0\" to \"${count - 1}\") as the key and that one line's translation as the value. All $count keys are required. Never put more than one line in a value, and never merge, split, skip or add lines.")
            } else {
                appendLine("1. Return exactly $count translations in \"translations\", one array item per input line, in the same order. Never put several lines in one item, and never merge, split, skip, reorder or add lines.")
            }
            appendLine("2. Read the whole song first. Keep one consistent narrator voice, speech level and tone across all lines.")
            if (korean) {
                when (translationSpeech) {
                    SPEECH_CASUAL -> appendLine("   For Korean, write every line in casual speech (반말). Never switch to 존댓말.")
                    SPEECH_POLITE -> appendLine("   For Korean, write every line in polite speech (존댓말, 해요체). Never switch to 반말.")
                    else -> appendLine("   For Korean, pick either casual speech (반말) or polite speech (존댓말) to suit the song and use it for every line. Never mix them. Most songs read naturally in 반말.")
                }
                appendLine("   Write natural modern Korean as on an official Korean lyric sheet, not translationese. Avoid stiff words such as '당신' unless the song truly calls for it.")
            }
            if (translationMode == MODE_LITERAL) {
                appendLine("3. Stay close to the original words and sentence structure: a faithful, literal translation that a learner can line up with the source. Render idioms by their plain meaning rather than replacing them with a target-language idiom. Keep each line roughly as short as the original.")
            } else {
                appendLine("3. Translate meaning and feeling, not word for word. Keep metaphors, idioms and wordplay meaningful in the target language. Keep each line roughly as short as the original.")
            }
            appendLine("4. Keep names of people, places, brands and titles as they are. Lines that are only vocal sounds (oh, yeah, la la, na na) may be kept as they are.")
            appendLine("5. Items with role \"section\" are labels such as Verse, Chorus or Bridge. Translate them as short labels.")
            appendLine("6. Items with role \"backing\" are background vocals. Translate them like the other lines.")
            appendLine("7. Output only the translated text. No quotes around lines, no notes, no romanisation, no explanations.")
            appendLine("8. Set \"source_language\" to the ISO 639-1 code of the song's main original language.")
            appendLine("9. Leave \"declined_reason\" as an empty string. Only if you will not translate this song, write the reason there in one short sentence and leave every translation empty. Never write a refusal, a warning or a note in place of a translation.")
        }
    }

    private suspend fun requestClaudeTranslation(slots: List<TextSlot>, target: String): ClaudeResult<BatchAnswer> {
        val input = buildJsonObject {
            put("target_language", target)
            put("line_count", slots.size)
            putJsonArray("lines") {
                slots.forEachIndexed { index, slot ->
                    add(buildJsonObject {
                        put("i", index)
                        put(
                            "role",
                            when {
                                slot.sectionHeader -> "section"
                                slot.background -> "backing"
                                else -> "lyric"
                            },
                        )
                        put("text", slot.text)
                    })
                }
            }
        }.toString()
        suspend fun ask(keyed: Boolean) = askClaude(
            purpose = ClaudeDiagnostics.PURPOSE_TRANSLATE,
            system = translationInstruction(target, slots.size, keyed),
            input = input,
            schema = if (keyed) keyedTranslationSchema(slots.size) else TRANSLATION_SCHEMA,
            effort = "medium",
            linesAsked = slots.size,
        ) { text -> parseTranslation(text, slots.map { it.text }, keyed) }
        // One required key per line holds the answer to the line count: the
        // structured output cannot end until every line has its own value,
        // which an array — any length satisfies "an array of strings" — never
        // did. A schema that large may still be refused for a very long song;
        // the array, with the split-up repair in [parseTranslation], is then
        // the next best thing.
        if (slots.size > MAX_KEYED_LINES) return ask(keyed = false)
        val keyed = ask(keyed = true)
        return if (keyed.failure == ClaudeFailure.BAD_REQUEST) ask(keyed = false) else keyed
    }

    /** Past this many lines the per-line schema is not tried; see [requestClaudeTranslation]. */
    private const val MAX_KEYED_LINES = 200

    /**
     * `{"source_language": "...", "declined_reason": "", "translations": {"0": "...", …, "<count-1>": "..."}}`,
     * every key required. "declined_reason" gives a model that will not
     * translate somewhere to say so other than in every line.
     */
    private fun keyedTranslationSchema(count: Int): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("source_language") { put("type", "string") }
            putJsonObject("declined_reason") { put("type", "string") }
            putJsonObject("translations") {
                put("type", "object")
                putJsonObject("properties") {
                    for (index in 0 until count) putJsonObject(index.toString()) { put("type", "string") }
                }
                putJsonArray("required") {
                    for (index in 0 until count) add(index.toString())
                }
                put("additionalProperties", false)
            }
        }
        putJsonArray("required") {
            add("source_language")
            add("declined_reason")
            add("translations")
        }
        put("additionalProperties", false)
    }

    private val TRANSLATION_SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("source_language") { put("type", "string") }
                putJsonObject("declined_reason") { put("type", "string") }
                putJsonObject("translations") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") {
                add("source_language")
                add("declined_reason")
                add("translations")
            }
            put("additionalProperties", false)
        }
    }

    /** What a caller made of Claude's answer text: the value, or why it is unusable. */
    internal class Parsed<T : Any>(
        val value: T?,
        val failure: ClaudeFailure? = null,
        val linesGot: Int? = null,
        val detail: String? = null,
    )

    /** The outcome of [askClaude]: the parsed value, or why there is none. */
    internal class ClaudeResult<T : Any>(val value: T?, val failure: ClaudeFailure?)

    /**
     * One Messages API request: [system] as the instructions, [input] as the
     * user's turn, the answer held to the JSON [schema] and read by [parse].
     * Every call — sent or not, answered or not — leaves an entry in
     * [ClaudeDiagnostics]; the callers all have somewhere else to go on failure.
     */
    internal suspend fun <T : Any> askClaude(
        purpose: String,
        system: String,
        input: String,
        schema: JsonObject,
        effort: String,
        maxTokens: Int = 16_000,
        /** Lines sent, for a translation: logged beside the count that came back. */
        linesAsked: Int? = null,
        parse: (String) -> Parsed<T>,
    ): ClaudeResult<T> {
        val key = cleanClaudeKey()
        val model = claudeModel
        val started = System.nanoTime()
        suspend fun done(
            failure: ClaudeFailure?,
            value: T? = null,
            httpCode: Int? = null,
            stopReason: String? = null,
            linesGot: Int? = null,
            inputTokens: Long? = null,
            outputTokens: Long? = null,
            detail: String? = null,
            sent: Boolean = true,
        ): ClaudeResult<T> {
            ClaudeDiagnostics.record(
                ClaudeDiagnostics.Entry(
                    at = System.currentTimeMillis(),
                    purpose = purpose,
                    model = model,
                    failure = failure?.name,
                    httpCode = httpCode,
                    stopReason = stopReason,
                    elapsedMs = if (sent) (System.nanoTime() - started) / 1_000_000L else 0L,
                    linesAsked = linesAsked,
                    linesGot = linesGot,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    detail = detail,
                ),
            )
            return ClaudeResult(value, failure)
        }
        if (key.isEmpty()) return ClaudeResult(null, ClaudeFailure.KEY_REJECTED)
        if (key == rejectedClaudeKey) {
            return done(ClaudeFailure.KEY_REJECTED, detail = "not sent: this key was refused earlier", sent = false)
        }
        val now = System.currentTimeMillis()
        if (now < claudeCooldownUntil) {
            val seconds = (claudeCooldownUntil - now + 999) / 1000
            return done(ClaudeFailure.COOLDOWN, detail = "not sent: resting ${seconds}s after a rate limit or overload", sent = false)
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", input)
                })
            }
            putJsonObject("output_config") {
                put("effort", effort)
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema)
                }
            }
        }.toString()
        val request = Request.Builder()
            .url(CLAUDE_ENDPOINT)
            .header("x-api-key", key)
            .header("anthropic-version", CLAUDE_API_VERSION)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val (code, text) = try {
            claudeClient.newCall(request).awaitResponse()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // OkHttp's call and read deadlines both surface as an interrupted read.
            val failure = if (error is java.io.InterruptedIOException) ClaudeFailure.TIMEOUT else ClaudeFailure.NETWORK
            return done(failure, detail = "${error::class.java.simpleName}: ${error.message}")
        }
        if (code in 200..299) {
            recordUsage(model, text)
            val meta = responseMeta(text)
            when (meta.stopReason) {
                "refusal" -> return done(
                    ClaudeFailure.REFUSAL, httpCode = code, stopReason = meta.stopReason,
                    inputTokens = meta.inputTokens, outputTokens = meta.outputTokens, detail = meta.refusalCategory,
                )
                "max_tokens" -> return done(
                    ClaudeFailure.CUT_OFF, httpCode = code, stopReason = meta.stopReason,
                    inputTokens = meta.inputTokens, outputTokens = meta.outputTokens,
                )
            }
            val answer = claudeText(text) ?: return done(
                ClaudeFailure.BAD_ANSWER, httpCode = code, stopReason = meta.stopReason,
                inputTokens = meta.inputTokens, outputTokens = meta.outputTokens, detail = "no text block in the answer",
            )
            val parsed = try {
                parse(answer)
            } catch (error: Exception) {
                Parsed<T>(null, ClaudeFailure.BAD_ANSWER, detail = error.toString())
            }
            val failure = parsed.failure ?: if (parsed.value == null) ClaudeFailure.BAD_ANSWER else null
            return done(
                failure, value = parsed.value?.takeIf { failure == null }, httpCode = code, stopReason = meta.stopReason,
                linesGot = parsed.linesGot, inputTokens = meta.inputTokens, outputTokens = meta.outputTokens,
                detail = parsed.detail,
            )
        }
        val failure = when {
            // A bad key, or an account with nothing left to spend, fails the
            // same every time: stop asking with it and let Settings say why.
            code == 401 || code == 403 -> {
                rejectedClaudeKey = key
                claudeKeyRejected.value = true
                ClaudeFailure.KEY_REJECTED
            }
            code == 400 && text.contains("credit balance", ignoreCase = true) -> {
                rejectedClaudeKey = key
                claudeKeyRejected.value = true
                ClaudeFailure.NO_CREDIT
            }
            // Rate limited or overloaded: rest a minute rather than delay
            // every translation in the meantime.
            code == 429 -> {
                claudeCooldownUntil = System.currentTimeMillis() + CLAUDE_COOLDOWN_MS
                ClaudeFailure.RATE_LIMIT
            }
            code == 529 -> {
                claudeCooldownUntil = System.currentTimeMillis() + CLAUDE_COOLDOWN_MS
                ClaudeFailure.OVERLOADED
            }
            code >= 500 -> ClaudeFailure.SERVER_ERROR
            code in 400..499 -> ClaudeFailure.BAD_REQUEST
            else -> ClaudeFailure.ERROR
        }
        return done(failure, httpCode = code, detail = apiErrorMessage(text))
    }

    private class ResponseMeta(
        val stopReason: String?,
        val refusalCategory: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
    )

    /** The stop reason, refusal category and token counts of an answer, whatever else it holds. */
    private fun responseMeta(response: String): ResponseMeta = runCatching {
        val root = json.parseToJsonElement(response).jsonObject
        val usage = root["usage"] as? JsonObject
        ResponseMeta(
            stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull,
            refusalCategory = (root["stop_details"] as? JsonObject)?.let { details ->
                listOfNotNull(
                    details["category"]?.jsonPrimitive?.contentOrNull,
                    details["explanation"]?.jsonPrimitive?.contentOrNull,
                ).joinToString(": ").ifBlank { null }
            },
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        )
    }.getOrElse { ResponseMeta(null, null, null, null) }

    /** "rate_limit_error: Number of request tokens has exceeded…" out of an error body, or its start. */
    private fun apiErrorMessage(response: String): String = runCatching {
        val error = json.parseToJsonElement(response).jsonObject["error"]?.jsonObject
        listOfNotNull(
            error?.get("type")?.jsonPrimitive?.contentOrNull,
            error?.get("message")?.jsonPrimitive?.contentOrNull,
        ).joinToString(": ").ifBlank { null }
    }.getOrNull() ?: response.take(200)

    /** What the answer cost, handed to [usageSink]; a response without a usage block counts nothing. */
    private fun recordUsage(model: String, response: String) {
        val sink = usageSink ?: return
        runCatching {
            val usage = json.parseToJsonElement(response).jsonObject["usage"]?.jsonObject ?: return
            val input = usage["input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val output = usage["output_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            if (input > 0 || output > 0) sink(model, input, output)
        }
    }

    /**
     * The answer's text out of a Messages API response — only its `text`
     * blocks, not thinking blocks. Null for a refusal or an answer cut short,
     * which can't be trusted to match the schema.
     */
    private fun claudeText(response: String): String? = runCatching {
        val root = json.parseToJsonElement(response).jsonObject
        val stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stop == "refusal" || stop == "max_tokens") return@runCatching null
        root["content"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun parseTranslation(text: String, sources: List<String>, keyed: Boolean): Parsed<BatchAnswer> {
        val expected = sources.size
        val answer = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Parsed(null, ClaudeFailure.BAD_ANSWER, detail = "not a JSON object (${text.length} chars)")
        val source = answer["source_language"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        fun primitive(element: kotlinx.serialization.json.JsonElement?): String? =
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()

        // The model's own word that it will not translate. Its reason is the
        // model's sentence, not the lyrics, so it goes in the log.
        primitive(answer["declined_reason"])?.takeIf { it.isNotEmpty() }?.let { reason ->
            return Parsed(null, ClaudeFailure.REFUSAL, detail = "declined: ${reason.take(160)}")
        }

        val field = answer["translations"]
        val translations: List<String> = when {
            keyed && field is JsonObject -> {
                val values = (0 until expected).map { primitive(field[it.toString()]) }
                val missing = values.indices.filter { values[it] == null }
                if (missing.isNotEmpty()) {
                    return Parsed(
                        null, ClaudeFailure.LINE_MISMATCH, linesGot = expected - missing.size,
                        detail = "keyed answer missing ${missing.size} keys (first: ${missing.take(5).joinToString()})",
                    )
                }
                val lines = values.map { it.orEmpty() }
                // Several lines crammed into one value, leaving others blank, is
                // the array failure again in another shape: not usable as is.
                val crammed = lines.count { it.contains('\n') }
                val blank = lines.count { it.isBlank() }
                if (crammed > 0 && blank > 0) {
                    return Parsed(
                        null, ClaudeFailure.LINE_MISMATCH, linesGot = expected - blank,
                        detail = "keyed answer: $crammed values hold several lines, $blank are blank",
                    )
                }
                lines
            }
            field is kotlinx.serialization.json.JsonArray -> {
                val items = field.map { primitive(it).orEmpty() }
                if (items.size == expected) {
                    items
                } else {
                    // The failure seen in the log: every line in one item (or a
                    // few), separated by line breaks. Split back apart, it is the
                    // answer — taken only when the count then comes out exact.
                    val split = items.flatMap { item -> item.split('\n').map(String::trim).filter(String::isNotEmpty) }
                    val breaks = items.sumOf { item -> item.count { it == '\n' } }
                    val shape = "items=${items.size}, line breaks inside=$breaks, split=${split.size}"
                    if (split.size != expected) {
                        return Parsed(null, ClaudeFailure.LINE_MISMATCH, linesGot = items.size, detail = shape)
                    }
                    return finishTranslation(split, sources, source, text, detail = "repaired: $shape")
                }
            }
            else -> return Parsed(
                null, ClaudeFailure.BAD_ANSWER,
                detail = "\"translations\" is ${if (field == null) "missing" else field::class.simpleName}",
            )
        }
        return finishTranslation(translations, sources, source, text, detail = null)
    }

    private fun finishTranslation(
        translations: List<String>,
        sources: List<String>,
        source: String,
        text: String,
        detail: String?,
    ): Parsed<BatchAnswer> {
        if (translations.all { it.isBlank() }) {
            return Parsed(null, ClaudeFailure.BAD_ANSWER, linesGot = translations.size, detail = "every line blank")
        }
        repeatedAcrossLines(translations, sources)?.let { (lines, distinct) ->
            return Parsed(
                null, ClaudeFailure.REFUSAL, linesGot = translations.size,
                detail = "same sentence on $lines lines for $distinct different original lines",
            )
        }
        return Parsed(
            BatchAnswer(
                translations = translations,
                sourceLanguage = source.ifBlank { "und" },
                sourceWeight = text.length,
            ),
            linesGot = translations.size,
            detail = detail,
        )
    }

    /**
     * A refusal written into the lines: one sentence standing in for at least
     * half the song, under at least three different original lines. A repeated
     * chorus repeats its original too, so it never counts. Returns how many
     * lines carry the sentence and how many different originals they stand for,
     * or null when the answer looks like a translation.
     */
    private fun repeatedAcrossLines(translations: List<String>, sources: List<String>): Pair<Int, Int>? {
        val filled = translations.indices.filter { translations[it].isNotBlank() }
        if (filled.size < 3) return null
        val (_, indices) = filled.groupBy { translations[it].trim() }.maxByOrNull { it.value.size } ?: return null
        val distinct = indices.map { sources.getOrNull(it).orEmpty().trim().lowercase() }.toSet().size
        return if (indices.size * 2 >= filled.size && distinct >= 3) indices.size to distinct else null
    }

    /** Status code and body, for callers that branch on the code. */
    private suspend fun Call.awaitResponse(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (continuation.isActive) continuation.resume(it.code to body)
                }
            }
        })
    }

    private fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank()) {
                val header = Genius.isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,
                        text = if (header) line.text.removePrefix("[").removeSuffix("]").trim() else line.text,
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }

    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLanguage)
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) return@runCatching null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun requestRomanizationBatch(batch: Batch, target: String): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", target)
            .add("dt", "rm")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = romanizedBody.split(markerRegex).map(String::trim)
            if (source.isBlank() || parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                return@runCatching null
            }
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        sourceLanguage: String,
        target: String,
    ): List<String>? = coroutineScope {
        val source = canonicalLanguage(sourceLanguage)
        if (source.isBlank() || source == "en") return@coroutineScope null
        val answers = batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
            group.map { batch ->
                async {
                    val nativePayload = requestNativeScript(batch.payload, source) ?: return@async null
                    requestBatch(batch.copy(payload = nativePayload), target, source)
                }
            }.awaitAll()
        }
        if (answers.any { it == null }) null else answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val body = FormBody.Builder()
            .add("text", text)
            .add("itc", "$sourceLanguage-t-i0-und")
            .add("num", "1")
            .add("cp", "0")
            .add("cs", "1")
            .add("ie", "utf-8")
            .add("oe", "utf-8")
            .build()
        val request = Request.Builder()
            .url(INPUT_TOOLS_ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return@runCatching null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        slots.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
                    else other++
                }
            }
        }
        return latin > 0 && latin >= other * 4
    }

    private fun hasNonLatinLetters(slots: List<TextSlot>): Boolean = slots.any { slot ->
        slot.text.codePoints().anyMatch { codePoint ->
            Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
        }
    }

    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (!continuation.isActive) return
                    if (body != null) continuation.resume(body)
                    else continuation.resumeWithException(IOException("Translation HTTP ${it.code}"))
                }
            }
        })
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) -> if (slot.sectionHeader) "[$text]" else text }
                ?: line.text
            line.copy(
                text = leadText,
                words = retimeWords(line, leadText),
                timingSource = line.takeIf { it.isWordSynced },
                background = line.background?.let { source ->
                    val text = backing?.second ?: source.text
                    source.copy(
                        text = text,
                        words = retimeWords(source, text),
                        timingSource = source.takeIf { it.isWordSynced },
                    )
                },
            )
        }
    }

    /**
     * Keep the source vocal bounds without inventing translated word timings.
     * LyricLine.timingSource projects the original non-uniform character sweep
     * onto the new text, preserving holds, pauses and the original glow envelope.
     */
    private fun retimeWords(source: LyricLine, translated: String): List<LyricWord> {
        if (source.words.isEmpty()) return emptyList()
        if (translated.isEmpty()) return emptyList()
        val start = source.words.first().startMs
        return listOf(LyricWord(start, source.words.last().endMs, translated))
    }

    private fun canonicalLanguage(tag: String): String = when (
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun readCache(key: String): CachedTranslation? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock null
                val file = File(File(root, CACHE_DIRECTORY), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedTranslation>(it.readText())
                    }
                    file.setLastModified(System.currentTimeMillis())
                    value.takeIf { it.version == CACHE_VERSION }
                }.getOrNull()
            }
        }

    private suspend fun writeCache(key: String, value: CachedTranslation) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val directory = File(root, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json.gz")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                        it.write(json.encodeToString(value))
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    trimCache(directory)
                }.onFailure { temporary.delete() }
            }
        }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > MAX_CACHE_BYTES) file.delete()
        }
    }

    // ---- What a song means ---------------------------------------------------

    @Serializable
    data class SongMeaning(
        /** One line on what the song is about. */
        val summary: String,
        /** A few short paragraphs, separated by blank lines. */
        val meaning: String,
        val highlights: List<MeaningHighlight> = emptyList(),
    )

    /** One line of the song quoted, and what it says. */
    @Serializable
    data class MeaningHighlight(val line: String, val note: String)

    sealed interface MeaningResult {
        data class Ready(val meaning: SongMeaning) : MeaningResult
        /** No Claude key is set, or Claude refused the one that is. */
        data object NoKey : MeaningResult
        data object Unavailable : MeaningResult
    }

    private val meanings = object {
        private val entries = object : LinkedHashMap<String, SongMeaning>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, SongMeaning>) = size > 8
        }

        @Synchronized fun get(key: String): SongMeaning? = entries[key]

        @Synchronized fun put(key: String, value: SongMeaning) {
            entries[key] = value
        }
    }

    /**
     * Claude's explanation of the song — what it is about, its images and
     * slang, a few lines worth a note — written in [targetLanguageTag].
     * Asked once per song and language, then kept for good.
     */
    suspend fun explain(
        trackId: String,
        title: String,
        artist: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): MeaningResult {
        val target = targetLanguageTag.trim().ifBlank { "en" }
        val key = cacheKey("meaning\u0000$trackId", target, emptyList())
        meanings.get(key)?.let { return MeaningResult.Ready(it) }
        readStored(MEANING_DIRECTORY, key)
            ?.let { text -> runCatching { json.decodeFromString<SongMeaning>(text) }.getOrNull() }
            ?.let {
                meanings.put(key, it)
                return MeaningResult.Ready(it)
            }
        if (!claudeKeyUsable) return MeaningResult.NoKey

        val lyricText = lines
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(MAX_MEANING_LYRIC_CHARS)
        if (lyricText.isBlank()) return MeaningResult.Unavailable

        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
            put("lyrics", lyricText)
        }
        val answer = try {
            askClaude(
                purpose = ClaudeDiagnostics.PURPOSE_MEANING,
                system = meaningInstruction(target),
                input = input.toString(),
                schema = MEANING_SCHEMA,
                effort = "medium",
            ) { text ->
                parseMeaning(text)?.let { Parsed<SongMeaning>(it) }
                    ?: Parsed<SongMeaning>(null, ClaudeFailure.BAD_ANSWER, detail = "summary or meaning missing")
            }.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (answer == null) {
            return if (claudeKeyUsable) MeaningResult.Unavailable else MeaningResult.NoKey
        }
        meanings.put(key, answer)
        writeStored(MEANING_DIRECTORY, key, json.encodeToString(answer), MAX_MEANING_BYTES)
        return MeaningResult.Ready(answer)
    }

    private val MEANING_SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("summary") { put("type", "string") }
                putJsonObject("meaning") { put("type", "string") }
                putJsonObject("highlights") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("line") { put("type", "string") }
                            putJsonObject("note") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("line")
                            add("note")
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") {
                add("summary")
                add("meaning")
                add("highlights")
            }
            put("additionalProperties", false)
        }
    }

    private fun meaningInstruction(target: String): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = canonicalLanguage(target) == "ko"
        return buildString {
            appendLine("You write short, insightful song explanations for a music app, like the notes on a lyric site.")
            appendLine("Write everything in $languageName ($target).")
            if (korean) {
                appendLine("Use natural, friendly modern Korean in 해요체. Never use '당신'. Avoid translationese.")
            }
            appendLine()
            appendLine("Return JSON with:")
            appendLine("- \"summary\": one sentence saying what the song is about${if (korean) " (under 60 Korean characters)" else ""}.")
            appendLine("- \"meaning\": two or three short paragraphs separated by one blank line. First the story and the feeling of the song, then its key images, metaphors and slang and what they mean.")
            appendLine("- \"highlights\": up to four items. Each has \"line\", copied exactly from the lyrics given, and \"note\", one or two sentences on what that line means.")
            appendLine()
            appendLine("Base everything on the lyrics given. Mention background about the artist or the song only if you are certain it is true. Never invent facts, chart records, interviews or quotes.")
            appendLine("Plain text only: no markdown, no bullet characters, no emojis.")
        }
    }

    private fun parseMeaning(text: String): SongMeaning? = runCatching {
        val parsed = json.decodeFromString<SongMeaning>(text)
        val cleaned = parsed.copy(
            summary = parsed.summary.trim(),
            meaning = parsed.meaning.trim(),
            highlights = parsed.highlights
                .map { MeaningHighlight(it.line.trim(), it.note.trim()) }
                .filter { it.line.isNotEmpty() && it.note.isNotEmpty() }
                .take(4),
        )
        cleaned.takeIf { it.summary.isNotEmpty() && it.meaning.isNotEmpty() }
    }.getOrNull()

    // ---- A song's name in its own language -----------------------------------

    /** What Claude made of a Hangul title: the original's name, or that there is none. */
    sealed interface NativeNameResult {
        data class Found(val title: String, val artist: String) : NativeNameResult
        /** Claude answered: the song is Korean, or it does not know it. Worth remembering. */
        data object NotForeign : NativeNameResult
        /** No key, no credit, no network: nothing learned, worth asking again later. */
        data object Unavailable : NativeNameResult
    }

    @Serializable
    private data class NativeNameAnswer(
        val found: Boolean = false,
        val title: String = "",
        val artist: String = "",
    )

    /**
     * The title and artist of a song as its original release prints them,
     * given the way a Korean listener or uploader wrote them in Hangul — "발묘 /
     * 나나호시 관현악단" for "抜錨 / ナナホシ管弦楽団". Lyrics catalogues file a
     * Japanese, Chinese or English song under its own name; a Hangul spelling
     * finds nothing in them.
     */
    suspend fun nativeName(title: String, artist: String): NativeNameResult {
        if (!claudeKeyUsable) return NativeNameResult.Unavailable
        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
        }
        val answer = try {
            askClaude(
                purpose = ClaudeDiagnostics.PURPOSE_ORIGINAL_NAME,
                system = NATIVE_NAME_INSTRUCTION,
                input = input.toString(),
                schema = NATIVE_NAME_SCHEMA,
                effort = "low",
                maxTokens = 8_000,
            ) { text ->
                runCatching { json.decodeFromString<NativeNameAnswer>(text) }.getOrNull()?.let { Parsed<NativeNameAnswer>(it) }
                    ?: Parsed<NativeNameAnswer>(null, ClaudeFailure.BAD_ANSWER, detail = "not the expected JSON: ${text.take(80)}")
            }.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return NativeNameResult.Unavailable
        val foundTitle = answer.title.trim()
        val foundArtist = answer.artist.trim()
        if (!answer.found || foundTitle.isEmpty()) return NativeNameResult.NotForeign
        // Asked for the original and handed the question back: nothing new.
        if (foundTitle == title.trim() && (foundArtist.isEmpty() || foundArtist == artist.trim())) {
            return NativeNameResult.NotForeign
        }
        return NativeNameResult.Found(foundTitle, foundArtist.ifEmpty { artist })
    }

    private val NATIVE_NAME_SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("found") { put("type", "boolean") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("artist") { put("type", "string") }
            }
            putJsonArray("required") {
                add("found")
                add("title")
                add("artist")
            }
            put("additionalProperties", false)
        }
    }

    private val NATIVE_NAME_INSTRUCTION = """
        You identify songs for a lyrics search. The input is a song title and artist as a Korean listener wrote them, usually in Hangul.
        Often the song is not Korean: it is a Japanese, Chinese or English song (J-pop, Vocaloid, anime, C-pop, Western pop) whose names were transliterated or translated into Hangul, for example a Korean cover of it.
        If you are confident which original song this is, return found=true with "title" and "artist" exactly as the original release writes them, in the original script (kanji/kana, Chinese characters, Latin letters). Example: {"title":"발묘","artist":"나나호시 관현악단"} -> {"found":true,"title":"抜錨","artist":"ナナホシ管弦楽団"}.
        If the song is originally Korean, or you are not confident, return found=false with empty strings. Never guess.
    """.trimIndent()

    // ---- Small answers kept as plain files ----------------------------------------

    private suspend fun readStored(directoryName: String, key: String): String? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = keepDir ?: cacheDir ?: return@withLock null
                val file = File(File(root, directoryName), "$key.json")
                if (!file.isFile) return@withLock null
                runCatching {
                    file.readText().also { file.setLastModified(System.currentTimeMillis()) }
                }.getOrNull()
            }
        }

    private suspend fun writeStored(directoryName: String, key: String, text: String, maxBytes: Long) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = keepDir ?: cacheDir ?: return@withLock
                val directory = File(root, directoryName)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    temporary.writeText(text)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    val files = directory.listFiles { file -> file.extension == "json" }
                        ?.sortedByDescending(File::lastModified)
                        .orEmpty()
                    var kept = 0L
                    files.forEach { file ->
                        kept += file.length()
                        if (kept > maxBytes) file.delete()
                    }
                }.onFailure { temporary.delete() }
            }
        }
}
