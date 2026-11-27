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
            /** Haiku declined the song and Sonnet translated it instead. */
            val sonnetStandIn: Boolean = false,
            /** From the kept store, which nothing clears, rather than the cache; only means anything [fromCache]. */
            val kept: Boolean = false,
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

    /**
     * KO: furigana for a song, one entry per lyric line — the line's markup
     * ([Furigana.segments]) or null where it has no reading to show.
     */
    data class FuriganaLines(val lines: List<String?>, val fromCache: Boolean)

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
    // Either private-use character closes it: a Chinese answer (zh-CN, zh-TW)
    // comes back with the closing U+E001 turned into a second U+E000, and a
    // marker that never closed left the whole song as one part, so every
    // translation into Chinese came back Unavailable.
    private val markerRegex = Regex("\\uE000[^\\uE000\\uE001]*[\\uE000\\uE001]")

    /** A batch's answer, cut back into one string per line it was sent. */
    internal fun splitMarked(body: String): List<String> = body.split(markerRegex).map(String::trim)
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

        @Synchronized fun clear() {
            entries.clear()
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

    /**
     * Translations of downloaded songs live here, under [keepDir], rather than
     * in the cache that the system and [MAX_CACHE_BYTES] both clear: a
     * downloaded song is played offline, where nothing could be asked again.
     */
    private const val KEPT_DIRECTORY = "lyrics_translation_kept_v1"
    private const val MAX_KEPT_BYTES = 100L * 1024L * 1024L

    /** The cache's size limit, for Settings to show beside what it holds. */
    const val CACHE_LIMIT_BYTES = MAX_CACHE_BYTES

    /** The kept store's size limit, which Settings can raise; past it the least recently used go. */
    @Volatile
    var keptLimitBytes: Long = MAX_KEPT_BYTES

    /**
     * Every Claude answer — translation or pronunciation — goes to the kept
     * store, not only a downloaded song's: each one was paid for, and the
     * cache's limit would otherwise drop the oldest of them.
     */
    @Volatile
    var keepClaudeAlways: Boolean = true

    private fun keepClaude(trackId: String): Boolean = keepClaudeAlways || keepTranslationFor(trackId)

    /** Whether a track's translation goes to the kept store — downloaded, and the setting on. */
    @Volatile
    var keepTranslationFor: (String) -> Boolean = { false }
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
        /** Sonnet's answer, asked because Haiku declined the song. */
        val sonnetStandIn: Boolean = false,
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

    internal data class BatchAnswer(
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
        /**
         * Only what is already cached or kept; never a request. For screens
         * that show a translation the player has made but must not pay for one
         * themselves, such as the lock screen.
         */
        cacheOnly: Boolean = false,
        /** Where the request came from, for [storedAnswers]: [VIA_PLAYER] or [VIA_DOWNLOAD]. */
        via: String = VIA_PLAYER,
        /**
         * Google Translate only, Claude left out — for a song whose Claude
         * answer is already paid for in a batch still out, so the player
         * shows something now without paying for the same answer twice.
         */
        googleOnly: Boolean = false,
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
        val claudeTried = !googleOnly && claudeEnabled && cleanClaudeKey().isNotEmpty()
        var claudeFailure: ClaudeFailure? = null
        if (claudeTried) {
            val attempt = try {
                claudeEntry(trackId, slots, target, fresh, cacheOnly, via)
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
                        sonnetStandIn = entry.sonnetStandIn,
                        kept = attempt.kept,
                    )
                }
            }
        }

        val cacheKey = cacheKey(trackId, target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            if (keepTranslationFor(trackId) && !isKept(cacheKey)) writeCache(cacheKey, cached, keep = true)
            val keptNow = isKept(cacheKey)
            noteStored(cacheKey, trackId, KIND_TRANSLATION, cached, target, model = null, kept = keptNow, via = null)
            writeLineMap(googlePrefix(trackId), target, slots, cached, keptNow, onlyOnce = true)
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    lines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                    claudeFellBack = claudeTried,
                    claudeFailure = claudeFailure,
                    kept = keptNow,
                )
            }
        }
        if (cacheOnly) return Result.Unavailable

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
        val keepGoogle = keepTranslationFor(trackId) || isKept(cacheKey)
        writeCache(cacheKey, entry, keep = keepGoogle)
        noteStored(cacheKey, trackId, KIND_TRANSLATION, entry, target, model = null, kept = keepGoogle && keepDir != null, via = via)
        writeLineMap(googlePrefix(trackId), target, slots, entry, keepGoogle)

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
        /** Only what is already cached; never a request. */
        cacheOnly: Boolean = false,
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
        if (cacheOnly) return RomanizationResult.Unavailable

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
            // A key saved again — after topping up, say — is tried again: the
            // refusal held for the old value would otherwise outlive the
            // "ready" Settings now shows.
            rejectedClaudeKey = null
            claudeKeyRejected.value = false
        }

    /**
     * Forgets that Claude refused the key, so the next translation tries it
     * again — after credit was topped up, say. Sends nothing itself: a key
     * still refused is refused again, free, on that next request.
     */
    internal fun rejectKey(key: String) {
        rejectedClaudeKey = key
        claudeKeyRejected.value = true
    }

    fun clearClaudeKeyRejection() {
        rejectedClaudeKey = null
        claudeKeyRejected.value = false
    }

    /** True once Claude refused the current key (or its account has no credit), so Settings can say why. */
    val claudeKeyRejected = MutableStateFlow(false)

    /** Which Claude answers: [CLAUDE_HAIKU] by default, [CLAUDE_SONNET] for a better read at a higher price. */
    @Volatile
    var claudeModel: String = CLAUDE_HAIKU

    /** The register the translation is written in, in any language that has one: [SPEECH_AUTO], [SPEECH_CASUAL] or [SPEECH_POLITE]. */
    @Volatile
    var translationSpeech: String = SPEECH_AUTO

    /** How close to the words: [MODE_NATURAL] (idiomatic) or [MODE_LITERAL] (close to the original). */
    @Volatile
    var translationMode: String = MODE_NATURAL

    /**
     * True once this month's Claude spending has reached the limit the user
     * set; nothing more is sent until the month turns or the limit is raised.
     */
    @Volatile
    var claudeOverBudget: () -> Boolean = { false }

    /**
     * True once today's spending on answers asked as they are needed has
     * reached the daily limit; batches are not held back by it.
     */
    @Volatile
    var claudeOverDailyBudget: () -> Boolean = { false }

    /** Ask Sonnet once when Haiku declines a song, rather than going straight to Google. */
    @Volatile
    var sonnetStandInEnabled: Boolean = true

    /** Write how lyrics are pronounced, in the reader's own script, with Claude — see [claudeReading]. */
    @Volatile
    var claudeReadingEnabled: Boolean = true

    /**
     * The listener's own instructions for Claude's translations and readings
     * ("keep names as they are", "no formal speech"…), from Settings; blank
     * for none. Appended to the instruction in a block of its own, after the
     * rules that fix the answer's form, which the structured output enforces.
     */
    @Volatile
    var listenerInstructions: String = ""

    /** The listener's instructions also go with the song explanations. */
    @Volatile
    var listenerInstructionsForMeaning: Boolean = false

    private fun StringBuilder.appendListenerInstructions() {
        val extra = listenerInstructions.trim()
        if (extra.isEmpty()) return
        appendLine()
        appendLine("The listener has added their own instructions. Follow them wherever they apply, as long as the answer keeps the form and the line count set out above:")
        appendLine("<listener_instructions>")
        appendLine(extra)
        appendLine("</listener_instructions>")
    }

    /** Told what every Claude answer cost in tokens, so the app can keep a tally. */
    @Volatile
    var usageSink: ((model: String, inputTokens: Long, outputTokens: Long) -> Unit)? = null

    /** Told how many web searches an answer made: billed per search, beside the tokens. */
    @Volatile
    var searchSink: ((searches: Int) -> Unit)? = null

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
        cacheOnly: Boolean = false,
        via: String = VIA_PLAYER,
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
            if (keepClaude(trackId) && !isKept(cacheKey)) writeCache(cacheKey, cached, keep = true)
            val keptNow = isKept(cacheKey)
            noteStored(
                cacheKey, trackId, KIND_TRANSLATION, cached, target,
                model = if (cached.sonnetStandIn) CLAUDE_SONNET else null, kept = keptNow, via = null, styled = true,
            )
            writeLineMap(claudePrefix(trackId), target, slots, cached, keptNow, onlyOnce = true)
            return ClaudeAttempt(cached.copy(engine = ENGINE_CLAUDE), fromCache = true, failure = null, kept = keptNow)
        }
        if (!fresh) {
            val prefix = "claude\u0000$translationSpeech\u0000$translationMode\u0000$trackId"
            fromAhead(trackId, prefix, target, slots, report = !cacheOnly)?.let { ahead ->
                memory.put(cacheKey, ahead)
                writeCache(cacheKey, ahead, keep = true)
                noteStored(cacheKey, trackId, KIND_TRANSLATION, ahead, target, model = null, kept = keepDir != null, via = VIA_AHEAD, styled = true)
                return ClaudeAttempt(ahead, fromCache = true, failure = null, kept = keepDir != null)
            }
        }
        if (cacheOnly) return ClaudeAttempt(null, fromCache = false, failure = null)
        val model = claudeModel
        var reply = requestClaudeTranslation(slots, target, model, trackId = trackId)
        // Haiku turns some songs down as copyrighted text that Sonnet
        // translates; one more request, to Sonnet, before Google stands in.
        // Only for a refusal: any other failure would fail Sonnet the same way.
        val standIn = reply.failure == ClaudeFailure.REFUSAL && model == CLAUDE_HAIKU && sonnetStandInEnabled
        if (standIn) reply = requestClaudeTranslation(slots, target, CLAUDE_SONNET, trackId = trackId)
        val fresh = reply.value ?: return ClaudeAttempt(null, fromCache = false, failure = reply.failure ?: ClaudeFailure.ERROR)
        val entry = CachedTranslation(
            sourceLanguage = fresh.sourceLanguage,
            targetLanguage = target,
            // A line left empty (often a bare "oh" or "yeah") keeps the
            // original rather than showing a blank translation under it.
            texts = fresh.translations.mapIndexed { index, text -> text.ifBlank { slots[index].text } },
            engine = ENGINE_CLAUDE,
            sonnetStandIn = standIn,
        )
        memory.put(cacheKey, entry)
        // An older answer in the kept store is read before the cache, so a new
        // one — a retranslation — replaces it there rather than hide behind it.
        val keepClaude = keepClaude(trackId) || isKept(cacheKey)
        writeCache(cacheKey, entry, keep = keepClaude)
        noteStored(
            cacheKey, trackId, KIND_TRANSLATION, entry, target,
            model = if (standIn) CLAUDE_SONNET else model, kept = keepClaude && keepDir != null, via = via, styled = true,
        )
        writeLineMap(claudePrefix(trackId), target, slots, entry, keepClaude)
        return ClaudeAttempt(entry, fromCache = false, failure = null)
    }

    /** What [claudeEntry] came back with: the entry, or why there is none. */
    private class ClaudeAttempt(
        val entry: CachedTranslation?,
        val fromCache: Boolean,
        val failure: ClaudeFailure?,
        /** Read from the kept store rather than the cache. */
        val kept: Boolean = false,
    )

    /**
     * How an answer's lines are laid out. [KEYED]: one required key per line,
     * which the structured output cannot end without — but the API refuses
     * that schema as too large for a long song. [INDEXED]: an array of
     * `{"i", "t"}`, small enough for any song, where a line the model splits
     * in two keeps its number and is joined back. [ARRAY]: plain strings,
     * kept for batches sent before [INDEXED].
     */
    internal enum class AnswerShape { KEYED, INDEXED, ARRAY }

    private fun translationInstruction(target: String, count: Int, shape: AnswerShape): String {
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
            when (shape) {
                AnswerShape.KEYED -> appendLine("1. \"translations\" is an object with one key per input line: the line's index \"i\" (\"0\" to \"${count - 1}\") as the key and that one line's translation as the value. All $count keys are required. Never put more than one line in a value, and never merge, split, skip or add lines.")
                AnswerShape.INDEXED -> appendLine("1. \"translations\" is an array with exactly one item per input line, in order: {\"i\": the input line's index, \"t\": that one line's translation}, for every i from 0 to \"line_count\" - 1 (\"line_count\" is in the input). A long line stays one item, however long. Never merge, split, skip, reorder or add lines.")
                AnswerShape.ARRAY -> appendLine("1. Return exactly $count translations in \"translations\", one array item per input line, in the same order. Never put several lines in one item, and never merge, split, skip, reorder or add lines.")
            }
            appendLine("2. Read the whole song first. Keep one consistent narrator voice, speech level and tone across all lines.")
            val (casualHint, politeHint) = registerHints(target)
            when (translationSpeech) {
                SPEECH_CASUAL -> appendLine("   Write every line in the casual, intimate register of $languageName, as between close friends$casualHint. Never switch to a formal register.")
                SPEECH_POLITE -> appendLine("   Write every line in the polite register of $languageName$politeHint. Never switch to a casual register.")
                else -> appendLine("   If $languageName has casual and polite registers$casualHint$politeHint, pick the one that suits the song and use it for every line. Never mix them. Most songs read naturally in the casual one.")
            }
            appendLine("   Write natural, idiomatic modern $languageName as on an official $languageName lyric sheet, not translationese.")
            if (korean) {
                appendLine("   For Korean, avoid stiff words such as '당신' unless the song truly calls for it.")
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
            appendLine("10. The input may name the song (\"song\": title, artist) and give its \"background\" and \"phrase_notes\" from a web search. Use them only to choose the tone and the words, and to carry wordplay and double meanings across. They can be wrong: the lyrics come first. Never add to a line anything that is not in it.")
            appendLine("11. When \"song\" gives \"title_in_language\" — the title as readers of the target language know it — and the song's title is sung in a line, write it as that title every time, never spelled out in other words.")
            appendListenerInstructions()
        }
    }

    private fun readingInstruction(target: String, count: Int, shape: AnswerShape): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val script = when (canonicalLanguage(target)) {
            "ko" -> "in Hangul, the way Korean fans write foreign lyrics to sing along, for example 歌え -> 우타에, 君の名は -> 키미노 나와, I love you -> 아이 러브 유"
            "ja" -> "in katakana, the way Japanese fans write foreign lyrics to sing along, for example 사랑해 -> サランヘ"
            "ru" -> "in Cyrillic, using the usual Russian transcription of the source language (Polivanov for Japanese, Kontsevich for Korean)"
            "he" -> "in the Hebrew alphabet"
            "hi" -> "in Devanagari"
            "th" -> "in Thai script"
            else -> "in the Latin alphabet, using the source language's standard romanisation: Hepburn for Japanese, Revised Romanization for Korean, pinyin with tone marks for Mandarin, and the usual system for any other script"
        }
        return buildString {
            appendLine("You write how song lyrics sound, for a music app, so a $languageName ($target) speaker can sing along.")
            appendLine("For every input line, write how the original line is pronounced, $script.")
            appendLine()
            appendLine("Context: the listener is playing this song right now and its original lyrics are already on their screen; this reading is shown under each line. It is not published or shared anywhere.")
            appendLine()
            appendLine("Rules:")
            when (shape) {
                AnswerShape.KEYED -> appendLine("1. \"translations\" is an object with one key per input line: the line's index \"i\" (\"0\" to \"${count - 1}\") as the key and that one line's reading as the value. All $count keys are required. Never put more than one line in a value, and never merge, split, skip or add lines.")
                AnswerShape.INDEXED -> appendLine("1. \"translations\" is an array with exactly one item per input line, in order: {\"i\": the input line's index, \"t\": that one line's reading}, for every i from 0 to \"line_count\" - 1 (\"line_count\" is in the input). A long line stays one item, however long. Never merge, split, skip, reorder or add lines.")
                AnswerShape.ARRAY -> appendLine("1. Return exactly $count readings in \"translations\", one array item per input line, in the same order. Never put several lines in one item, and never merge, split, skip, reorder or add lines.")
            }
            appendLine("2. Write the sounds of the original words; never translate them. Read words the way the singer sings them, including the song's own readings of kanji.")
            appendLine("3. A line already written in that script, and names or words a $languageName reader would write as they are, may be kept as they are.")
            appendLine("4. Items with role \"section\" are labels such as Verse or Chorus: keep them as they are.")
            appendLine("5. Output only the reading. No quotes, no notes, no translation.")
            appendLine("6. Set \"source_language\" to the ISO 639-1 code of the song's main original language.")
            appendLine("7. Leave \"declined_reason\" as an empty string. Only if you will not do this, write the reason there in one short sentence and leave every value empty. Never write a refusal in place of a reading.")
            appendListenerInstructions()
        }
    }

    /** KO: the instruction for [Furigana]: each line copied as it is, its kanji words marked with their readings. */
    private fun furiganaInstruction(script: Furigana.Script, shape: AnswerShape): String = buildString {
        val written = when (script) {
            Furigana.Script.HIRAGANA -> "in hiragana"
            Furigana.Script.HANGUL -> "in Hangul, the way Korean fans write Japanese sounds (歌 -> 우타, 抜錨 -> 바츠뵤)"
        }
        appendLine("You add furigana to Japanese song lyrics for a music app: the reading of each kanji word, $written, shown in small type over it.")
        appendLine()
        appendLine("Context: the listener is playing this song right now and its lyrics are already on their screen; the readings are drawn over them. They are not published or shared anywhere.")
        appendLine()
        appendLine("Rules:")
        when (shape) {
            AnswerShape.INDEXED -> appendLine("1. \"translations\" is an array with exactly one item per input line, in order: {\"i\": the input line's index, \"t\": that line with its readings}, for every i from 0 to \"line_count\" - 1 (\"line_count\" is in the input). Never merge, split, skip, reorder or add lines.")
            else -> appendLine("1. Return one item per input line, in the same order. Never merge, split, skip, reorder or add lines.")
        }
        appendLine("2. Copy every line exactly, character for character: the same letters, kana, spaces and punctuation. The only change is to write each word that has kanji in it as {word|reading}, for example {抜錨|ばつびょう}を{見|み}て.")
        appendLine("3. Mark the whole kanji word, not one character at a time: {運命|うんめい}, not {運|うん}{命|めい}. Kana written after the kanji (okurigana) stay outside the braces: {見|み}て, {走|はし}り.")
        appendLine("4. Give the reading the singer sings, which may be the song's own: when 本気 is sung マジ, write {本気|まじ}.")
        appendLine("5. A line with no kanji is copied unchanged. Never mark kana, Latin letters or numbers, and never use {, | or } for anything else.")
        appendLine("6. Items with role \"section\" are labels such as Verse or Chorus: copy them unchanged.")
        appendLine("7. Set \"source_language\" to the ISO 639-1 code of the song's main original language.")
        appendLine("8. Leave \"declined_reason\" as an empty string. Only if you will not do this, write the reason there in one short sentence and leave every value empty.")
    }

    /**
     * How [target]'s casual and polite registers are usually named, as " (…)"
     * fragments for the instruction — empty for a language without a common
     * name for them, where the plain words "casual" and "polite" do.
     */
    private fun registerHints(target: String): Pair<String, String> {
        val (casual, polite) = when (canonicalLanguage(target)) {
            "ko" -> "반말" to "존댓말, 해요체"
            "ja" -> "タメ口" to "です・ます調"
            "zh" -> "你" to "您"
            "fr" -> "tu" to "vous"
            "de" -> "du" to "Sie"
            "es" -> "tú" to "usted"
            "it" -> "tu" to "Lei"
            "pt" -> "você/tu" to "o senhor/a senhora"
            "pl" -> "ty" to "Pan/Pani"
            "ru" -> "ты" to "вы"
            "tr" -> "sen" to "siz"
            "hi" -> "तुम" to "आप"
            "id" -> "kamu" to "Anda"
            "vi" -> "anh/em" to "bạn/quý vị"
            "th" -> "ฉัน/เธอ" to "ผม/ดิฉัน/คุณ"
            "he" -> "אתה/את" to "אתם"
            else -> return "" to ""
        }
        return " ($casual)" to " ($polite)"
    }

    private suspend fun requestClaudeTranslation(
        slots: List<TextSlot>,
        target: String,
        model: String,
        /** How each line sounds, in [target]'s script, rather than what it means — see [claudeReading]. */
        reading: Boolean = false,
        /** The song, for its name and any background kept for it; see [SongBackgrounds]. */
        trackId: String? = null,
        /** KO: each line copied with readings over its kanji, rather than translated; see [claudeFurigana]. */
        furigana: Furigana.Script? = null,
    ): ClaudeResult<BatchAnswer> {
        val input = translationInput(slots, target, trackId)
        suspend fun ask(shape: AnswerShape) = askClaude(
            purpose = when {
                furigana != null -> ClaudeDiagnostics.PURPOSE_FURIGANA
                reading -> ClaudeDiagnostics.PURPOSE_READING
                else -> ClaudeDiagnostics.PURPOSE_TRANSLATE
            },
            model = model,
            system = when {
                furigana != null -> furiganaInstruction(furigana, shape)
                reading -> readingInstruction(target, slots.size, shape)
                else -> translationInstruction(target, slots.size, shape)
            },
            input = input,
            schema = schemaFor(shape, slots.size),
            effort = if (furigana != null) furiganaEffort else "medium",
            linesAsked = slots.size,
            cache = true,
        ) { text -> parseTranslation(text, slots.map { it.text }, shape) }
        // The numbered array, as a batch asks: the per-line schema was refused
        // for a song of ordinary length, and a schema that changes with the
        // song changes the prompt's front with it, so the instruction could
        // never be read back from the prompt cache. The array's schema and the
        // instruction are the same for every song of one style.
        return ask(AnswerShape.INDEXED)
    }

    /** The user's turn of a translation or reading request: the lines, numbered, each with its role. */
    private fun translationInput(slots: List<TextSlot>, target: String, trackId: String? = null): String = buildJsonObject {
        put("target_language", target)
        put("line_count", slots.size)
        // The song's name, and its background where one was looked up: read
        // for tone and word choice, never added to the lines.
        SongBackgrounds.context(trackId)?.let { put("song", it) }
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

    private fun schemaFor(shape: AnswerShape, count: Int): JsonObject = when (shape) {
        AnswerShape.KEYED -> keyedTranslationSchema(count)
        AnswerShape.INDEXED -> INDEXED_TRANSLATION_SCHEMA
        AnswerShape.ARRAY -> TRANSLATION_SCHEMA
    }

    /** `{"source_language", "declined_reason", "translations": [{"i": 0, "t": "..."}, …]}`; see [AnswerShape.INDEXED]. */
    private val INDEXED_TRANSLATION_SCHEMA: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("source_language") { put("type", "string") }
                putJsonObject("declined_reason") { put("type", "string") }
                putJsonObject("translations") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("i") { put("type", "integer") }
                            putJsonObject("t") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("i")
                            add("t")
                        }
                        put("additionalProperties", false)
                    }
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
        /** The settings' model unless a caller needs a particular one. */
        model: String = claudeModel,
        input: String,
        schema: JsonObject,
        effort: String,
        maxTokens: Int = 16_000,
        /** Lines sent, for a translation: logged beside the count that came back. */
        linesAsked: Int? = null,
        /** Server tools the request may use; see [claudeBody]. */
        tools: kotlinx.serialization.json.JsonArray? = null,
        /** The instruction marked for the prompt cache; see [claudeBody]. */
        cache: Boolean = false,
        parse: (String) -> Parsed<T>,
    ): ClaudeResult<T> {
        val key = cleanClaudeKey()
        val started = System.nanoTime()
        // The answer's usage, once there is one: the cache figures go in the log whatever else does.
        var answered: ResponseMeta? = null
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
            searches: Int? = null,
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
                    searches = searches,
                    cacheRead = answered?.cacheRead,
                    cacheWrite = answered?.cacheWrite,
                    detail = detail,
                ),
            )
            return ClaudeResult(value, failure)
        }
        if (key.isEmpty()) return ClaudeResult(null, ClaudeFailure.KEY_REJECTED)
        if (key == rejectedClaudeKey) {
            return done(ClaudeFailure.KEY_REJECTED, detail = "not sent: this key was refused earlier", sent = false)
        }
        if (claudeOverBudget()) {
            return done(ClaudeFailure.BUDGET, detail = "not sent: this month's limit is reached", sent = false)
        }
        if (claudeOverDailyBudget()) {
            return done(ClaudeFailure.DAILY_BUDGET, detail = "not sent: today's limit is reached", sent = false)
        }
        val now = System.currentTimeMillis()
        if (now < claudeCooldownUntil) {
            val seconds = (claudeCooldownUntil - now + 999) / 1000
            return done(ClaudeFailure.COOLDOWN, detail = "not sent: resting ${seconds}s after a rate limit or overload", sent = false)
        }
        val body = claudeBody(model, system, input, schema, effort, maxTokens, tools, cache).toString()
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
            answered = meta
            // Each web search is billed on its own, beside the tokens.
            meta.searches?.takeIf { it > 0 }?.let { searches -> searchSink?.invoke(searches) }
            when (meta.stopReason) {
                "refusal" -> return done(
                    ClaudeFailure.REFUSAL, httpCode = code, stopReason = meta.stopReason,
                    inputTokens = meta.inputTokens, outputTokens = meta.outputTokens, detail = meta.refusalCategory,
                    searches = meta.searches,
                )
                "max_tokens" -> return done(
                    ClaudeFailure.CUT_OFF, httpCode = code, stopReason = meta.stopReason,
                    inputTokens = meta.inputTokens, outputTokens = meta.outputTokens, searches = meta.searches,
                )
            }
            val answer = claudeText(text) ?: return done(
                ClaudeFailure.BAD_ANSWER, httpCode = code, stopReason = meta.stopReason,
                inputTokens = meta.inputTokens, outputTokens = meta.outputTokens, detail = "no text block in the answer",
                searches = meta.searches,
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
                detail = parsed.detail, searches = meta.searches,
            )
        }
        val failure = when {
            // A bad key, or an account with nothing left to spend, fails the
            // same every time: stop asking with it and let Settings say why.
            code == 401 || code == 403 -> {
                rejectKey(key)
                ClaudeFailure.KEY_REJECTED
            }
            code == 400 && text.contains("credit balance", ignoreCase = true) -> {
                rejectKey(key)
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

    /** A Messages API request body — the same one whether sent now or in a batch. */
    private fun claudeBody(
        model: String,
        system: String,
        input: String,
        schema: JsonObject,
        effort: String,
        maxTokens: Int = 16_000,
        /** Server tools the request may use — the web search; null for none. */
        tools: kotlinx.serialization.json.JsonArray? = null,
        /**
         * Marks [system] for the prompt cache: the instruction is the same for
         * every song of one style, so a request within five minutes of the last
         * reads it back at a tenth of the price (a twentieth on Sonnet 5.5).
         */
        cache: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        if (cache) {
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", system)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
        } else {
            put("system", system)
        }
        if (tools != null) put("tools", tools)
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
    }

    private class ResponseMeta(
        val stopReason: String?,
        val refusalCategory: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val searches: Int? = null,
        val cacheRead: Long? = null,
        val cacheWrite: Long? = null,
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
            searches = (usage?.get("server_tool_use") as? JsonObject)
                ?.get("web_search_requests")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            cacheWrite = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
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
            // Read back from the prompt cache, and written to it: tallied apart, at their own prices.
            val read = usage["cache_read_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val written = usage["cache_creation_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            if (read > 0) sink(cacheModel(model, CACHE_READ_SUFFIX), read, 0L)
            if (written > 0) sink(cacheModel(model, CACHE_WRITE_SUFFIX), written, 0L)
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
        val texts = root["content"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .map { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        fun clean(text: String) = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val joined = clean(texts.joinToString(""))
        // A request that searched can say a word before its answer, in a text
        // block of its own: the answer is then the last block.
        val answer = if (texts.size > 1 && !joined.startsWith("{")) clean(texts.last()) else joined
        answer.takeIf { it.isNotEmpty() }
    }.getOrNull()

    internal fun parseTranslation(text: String, sources: List<String>, answerShape: AnswerShape): Parsed<BatchAnswer> {
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
            answerShape == AnswerShape.KEYED && field is JsonObject -> {
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
            field is kotlinx.serialization.json.JsonArray && field.any { it is JsonObject } -> {
                // Numbered items: each line's translation under its own index.
                // A line the model split keeps its number twice and is joined
                // back; a number out of range or missing means it lost count.
                val byIndex = HashMap<Int, MutableList<String>>()
                var outOfRange = 0
                field.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val index = (item["i"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
                    val value = primitive(item["t"]).orEmpty()
                    if (index == null || index !in 0 until expected) {
                        outOfRange++
                    } else {
                        byIndex.getOrPut(index) { mutableListOf() }.add(value)
                    }
                }
                val missing = (0 until expected).filter { it !in byIndex }
                val joined = byIndex.count { it.value.size > 1 }
                val shape = "items=${field.size}, joined=$joined, out of range=$outOfRange, missing=${missing.size}"
                if (missing.isNotEmpty() || outOfRange > 0) {
                    return Parsed(null, ClaudeFailure.LINE_MISMATCH, linesGot = expected - missing.size, detail = shape)
                }
                val lines = (0 until expected).map { index ->
                    byIndex.getValue(index).filter { it.isNotBlank() }.joinToString(" ").replace('\n', ' ').trim()
                }
                return finishTranslation(lines, sources, source, text, detail = if (joined > 0) "repaired: $shape" else null)
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
            val parts = splitMarked(translatedBody)
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
            val parts = splitMarked(romanizedBody)
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

    private fun keptFile(key: String): File? = keepDir?.let { File(File(it, KEPT_DIRECTORY), "$key.json.gz") }

    /** The kept store first — a downloaded song's answer — then the cache. */
    private suspend fun readCache(key: String): CachedTranslation? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val file = keptFile(key)?.takeIf { it.isFile }
                    ?: cacheDir?.let { File(File(it, CACHE_DIRECTORY), "$key.json.gz") }?.takeIf { it.isFile }
                    ?: return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedTranslation>(it.readText())
                    }
                    file.setLastModified(System.currentTimeMillis())
                    value.takeIf { it.version == CACHE_VERSION }
                }.getOrNull()
            }
        }

    /** Whether [key] is already in the kept store. */
    private suspend fun isKept(key: String): Boolean =
        withContext(Dispatchers.IO) { keptFile(key)?.isFile == true }

    /** To the kept store when [keep] (and there is one), otherwise to the cache. */
    private suspend fun writeCache(key: String, value: CachedTranslation, keep: Boolean = false) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val kept = keep && keepDir != null
                val directory = if (kept) {
                    File(keepDir ?: return@withLock, KEPT_DIRECTORY)
                } else {
                    File(cacheDir ?: return@withLock, CACHE_DIRECTORY)
                }
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
                    trimIfOver(directory, if (kept) keptLimitBytes else MAX_CACHE_BYTES, destination.length())
                    // Moved to the kept store: the cache's copy is only space now.
                    if (kept) {
                        cacheDir?.let { File(File(it, CACHE_DIRECTORY), "$key.json.gz") }?.takeIf { it.isFile }?.let { copy ->
                            val size = copy.length()
                            if (copy.delete()) cacheDir?.let { adjustSize(File(it, CACHE_DIRECTORY), -size) }
                        }
                    }
                    Unit
                }.onFailure { temporary.delete() }
            }
        }

    /**
     * Each store's size as last added up, so a write need not list and sort
     * a directory of thousands of files: only once the running total passes
     * the limit is it trimmed, and counted afresh. Called holding [diskMutex].
     */
    private val storeBytes = HashMap<String, Long>()

    private fun trimIfOver(directory: File, maxBytes: Long, added: Long) {
        val known = storeBytes[directory.path]
        if (known == null) {
            storeBytes[directory.path] = trimCache(directory, maxBytes)
            return
        }
        // An overwritten file counts twice here: the total can only run high,
        // which costs an early recount, never an overfull store.
        val total = known + added
        storeBytes[directory.path] = if (total > maxBytes) trimCache(directory, maxBytes) else total
    }

    /** After files went outside a write: what [trimIfOver] holds for [directory], corrected by [delta]. */
    private fun adjustSize(directory: File, delta: Long) {
        storeBytes[directory.path]?.let { storeBytes[directory.path] = (it + delta).coerceAtLeast(0L) }
    }

    /** Forgets every running total, for after changes too many to account for one by one. */
    private fun forgetSizes() = storeBytes.clear()

    /** Trims [directory] to [maxBytes], least recently used first; returns what is left. */
    private fun trimCache(directory: File, maxBytes: Long): Long {
        // Each file's time read once: a sort that asks the disk on every comparison is the slow part.
        val files = directory.listFiles { file -> file.extension == "gz" }
            .orEmpty()
            .map { Triple(it, it.lastModified(), it.length()) }
            .sortedByDescending { it.second }
        var kept = 0L
        var over = false
        files.forEach { (file, _, size) ->
            if (over || kept + size > maxBytes) {
                over = true
                file.delete()
            } else {
                kept += size
            }
        }
        return kept
    }

    // ---- How the lyrics sound, in the reader's own script --------------------

    /**
     * How each line is pronounced, written the way a [targetLanguageTag]
     * reader spells sounds — Hangul for Korean (歌え -> 우타에), katakana for
     * Japanese, the standard romanisation for languages in the Latin alphabet —
     * from Claude, for the romanize button.
     *
     * Null whenever Claude has nothing to add: it is off or not set up, the
     * reading setting is off, the lyrics are already in the reader's script,
     * or the request failed. The caller then uses [romanize], Google's
     * romanisation, as before. Kept per song and language like a translation.
     */
    suspend fun claudeReading(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        /** Asked for this one song even though Settings reads with the free reading: "ask Claude instead". */
        force: Boolean = false,
        /** Only a reading already stored or translated ahead, which costs nothing: never a request. */
        storedOnly: Boolean = false,
        /** Looked up on the player's own, not for a tap: lyrics that differ from the batch's are not reported. */
        quiet: Boolean = false,
    ): RomanizationResult? {
        if (!storedOnly && (!claudeEnabled || (!claudeReadingEnabled && !force) || cleanClaudeKey().isEmpty())) return null
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return null
        val slots = flatten(lines)
        if (slots.isEmpty() || alreadyInScriptOf(target, slots)) return null
        val cacheKey = cacheKey("reading\u0000$trackId", target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also { memory.put(cacheKey, it) }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size &&
            repeatedAcrossLines(cached.texts, slots.map { it.text }) == null
        ) {
            if (keepClaude(trackId) && !isKept(cacheKey)) writeCache(cacheKey, cached, keep = true)
            noteStored(cacheKey, trackId, KIND_READING, cached, target, model = null, kept = isKept(cacheKey), via = null)
            return RomanizationResult.Romanized(rebuild(lines, slots, cached.texts), cached.sourceLanguage, fromCache = true)
        }
        fromAhead(trackId, "reading\u0000$trackId", target, slots, report = !quiet)?.let { ahead ->
            memory.put(cacheKey, ahead)
            writeCache(cacheKey, ahead, keep = true)
            noteStored(cacheKey, trackId, KIND_READING, ahead, target, model = null, kept = keepDir != null, via = VIA_AHEAD)
            return RomanizationResult.Romanized(rebuild(lines, slots, ahead.texts), ahead.sourceLanguage, fromCache = true)
        }
        if (storedOnly) return null
        val model = claudeModel
        var reply = requestClaudeTranslation(slots, target, model, reading = true, trackId = trackId)
        var readingModel = model
        if (reply.failure == ClaudeFailure.REFUSAL && model == CLAUDE_HAIKU && sonnetStandInEnabled) {
            reply = requestClaudeTranslation(slots, target, CLAUDE_SONNET, reading = true, trackId = trackId)
            readingModel = CLAUDE_SONNET
        }
        val answer = reply.value ?: return null
        val entry = CachedTranslation(
            sourceLanguage = answer.sourceLanguage,
            targetLanguage = target,
            texts = answer.translations.mapIndexed { index, text -> text.ifBlank { slots[index].text } },
            engine = ENGINE_CLAUDE,
        )
        memory.put(cacheKey, entry)
        val keepReading = keepClaude(trackId) || isKept(cacheKey)
        writeCache(cacheKey, entry, keep = keepReading)
        noteStored(cacheKey, trackId, KIND_READING, entry, target, model = readingModel, kept = keepReading && keepDir != null, via = VIA_PLAYER)
        return RomanizationResult.Romanized(rebuild(lines, slots, entry.texts), entry.sourceLanguage, fromCache = false)
    }

    /**
     * KO: furigana for [lines] — each kanji word's reading in [script] — from
     * Claude, for the lyric panel's ふ button; kept per song and script like a
     * reading. Null when Claude is off or not set up, the song is not Japanese
     * with kanji ([Furigana.wanted]), or the request failed; [storedOnly] never
     * asks. Every line is held to its original by [Furigana.checked].
     */
    suspend fun claudeFurigana(
        trackId: String,
        lines: List<LyricLine>,
        script: Furigana.Script,
        storedOnly: Boolean = false,
    ): FuriganaLines? {
        if (!storedOnly && (!claudeEnabled || cleanClaudeKey().isEmpty())) return null
        if (lines.isEmpty()) return null
        val slots = flatten(lines)
        if (slots.isEmpty() || !Furigana.wanted(slots.map { it.text })) return null
        val target = script.tag
        val prefix = "furigana\u0000$trackId"
        val cacheKey = cacheKey(prefix, target, slots)
        fun shown(texts: List<String>, fromCache: Boolean) = FuriganaLines(furiganaByLine(lines, slots, texts, script), fromCache)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also { memory.put(cacheKey, it) }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            if (keepClaude(trackId) && !isKept(cacheKey)) writeCache(cacheKey, cached, keep = true)
            noteStored(cacheKey, trackId, KIND_FURIGANA, cached, target, model = null, kept = isKept(cacheKey), via = null)
            return shown(cached.texts, fromCache = true)
        }
        fromAhead(trackId, prefix, target, slots, report = false)?.let { ahead ->
            memory.put(cacheKey, ahead)
            writeCache(cacheKey, ahead, keep = true)
            noteStored(cacheKey, trackId, KIND_FURIGANA, ahead, target, model = null, kept = keepDir != null, via = VIA_AHEAD)
            return shown(ahead.texts, fromCache = true)
        }
        if (storedOnly) return null
        // Only the lines with kanji: the rest would come back exactly as sent, paid for line by line.
        val asked = furiganaLinesOf(slots)
        if (asked.isEmpty()) return null
        val askedSlots = asked.map { slots[it] }
        val model = claudeModel
        var reply = requestClaudeTranslation(askedSlots, target, model, trackId = trackId, furigana = script)
        var usedModel = model
        if (reply.failure == ClaudeFailure.REFUSAL && model == CLAUDE_HAIKU && sonnetStandInEnabled) {
            reply = requestClaudeTranslation(askedSlots, target, CLAUDE_SONNET, trackId = trackId, furigana = script)
            usedModel = CLAUDE_SONNET
        }
        val answer = reply.value ?: return null
        val entry = CachedTranslation(
            sourceLanguage = answer.sourceLanguage,
            targetLanguage = target,
            texts = expandFurigana(slots.map { it.text }, asked, answer.translations, script),
            engine = ENGINE_CLAUDE,
        )
        memory.put(cacheKey, entry)
        val keep = keepClaude(trackId) || isKept(cacheKey)
        writeCache(cacheKey, entry, keep = keep)
        noteStored(cacheKey, trackId, KIND_FURIGANA, entry, target, model = usedModel, kept = keep && keepDir != null, via = VIA_PLAYER)
        translationsChanged.value = translationsChanged.value + 1
        return shown(entry.texts, fromCache = false)
    }

    /** Each answer held to its line by [Furigana.checked]; a line that fails keeps its own words, with no reading. */
    private fun checkedFurigana(sources: List<String>, answers: List<String>, script: Furigana.Script): List<String> =
        sources.mapIndexed { index, source -> answers.getOrNull(index)?.let { Furigana.checked(source, it, script) } ?: source }

    /** Which of [slots] furigana is asked for: lines with a kanji, section labels aside. */
    private fun furiganaLinesOf(slots: List<TextSlot>): List<Int> =
        slots.indices.filter { !slots[it].sectionHeader && Furigana.hasKanji(slots[it].text) }

    /**
     * The answers for the lines asked ([asked], by place in [sources]) put
     * back among all of them, each held to its line; every other line as it is.
     */
    private fun expandFurigana(sources: List<String>, asked: List<Int>, answers: List<String>, script: Furigana.Script): List<String> {
        val texts = sources.toMutableList()
        asked.forEachIndexed { k, i ->
            val answer = answers.getOrNull(k) ?: return@forEachIndexed
            texts[i] = Furigana.checked(sources[i], answer, script) ?: sources[i]
        }
        return texts
    }

    /** The stored markup back on [lines]: the lead vocal's, line by line, null where it has no reading. */
    private fun furiganaByLine(lines: List<LyricLine>, slots: List<TextSlot>, texts: List<String>, script: Furigana.Script): List<String?> {
        val byLine = arrayOfNulls<String>(lines.size)
        slots.forEachIndexed { index, slot ->
            if (slot.background || slot.sectionHeader) return@forEachIndexed
            val text = texts.getOrNull(index) ?: return@forEachIndexed
            // Checked again on the way out: stored by an older build, or the lyrics' spacing changed.
            byLine[slot.lineIndex] = Furigana.checked(lines[slot.lineIndex].text, text, script)
        }
        return byLine.toList()
    }

    /**
     * Whether the lyrics' letters are already, four in five, in the script
     * [target]'s readers write — a Korean song for a Korean reader — where a
     * reading would only repeat them and Google's romanisation still has a use.
     */
    private fun alreadyInScriptOf(target: String, slots: List<TextSlot>): Boolean {
        val wanted: Set<Character.UnicodeScript> = when (canonicalLanguage(target)) {
            "ko" -> setOf(Character.UnicodeScript.HANGUL)
            "ja" -> setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
            "zh" -> setOf(Character.UnicodeScript.HAN)
            "ru" -> setOf(Character.UnicodeScript.CYRILLIC)
            "he" -> setOf(Character.UnicodeScript.HEBREW)
            "hi" -> setOf(Character.UnicodeScript.DEVANAGARI)
            "th" -> setOf(Character.UnicodeScript.THAI)
            else -> setOf(Character.UnicodeScript.LATIN)
        }
        var letters = 0
        var inScript = 0
        slots.filterNot { it.sectionHeader }.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    letters++
                    if (Character.UnicodeScript.of(codePoint) in wanted) inScript++
                }
            }
        }
        return letters == 0 || inScript * 5 >= letters * 4
    }

    // ---- Translating ahead, through the Message Batches API ------------------
    //
    // A playlist's songs asked for in one batch, at half the price, answered
    // within the day rather than within the minute. The app decides what goes
    // in a batch and remembers it; this builds each song's request, sends the
    // batch, and files each answer where the player will look for it.

    /** Added to a model's name in [usageSink] for tokens billed at the batch rate, half the list price. */
    const val BATCH_MODEL_SUFFIX = ":batch"

    /** Added to a model's name in [usageSink] for input read back from the prompt cache. */
    const val CACHE_READ_SUFFIX = ":cache_read"

    /** Added to a model's name in [usageSink] for input written to the prompt cache. */
    const val CACHE_WRITE_SUFFIX = ":cache_write"

    /** "claude-sonnet-5-5:cache_read", or "…:cache_read:batch" — the batch suffix stays last, where it is looked for. */
    fun cacheModel(model: String, suffix: String): String =
        if (model.endsWith(BATCH_MODEL_SUFFIX)) model.removeSuffix(BATCH_MODEL_SUFFIX) + suffix + BATCH_MODEL_SUFFIX else model + suffix

    /** The model a usage tally's name is about, whatever is added to it. */
    fun baseModel(model: String): String =
        model.removeSuffix(BATCH_MODEL_SUFFIX).removeSuffix(CACHE_READ_SUFFIX).removeSuffix(CACHE_WRITE_SUFFIX)
    private const val BATCH_ENDPOINT = "https://api.anthropic.com/v1/messages/batches"
    private const val AHEAD_PREFIX = "ahead\u0000"

    /**
     * Languages written in a script of their own, where lyrics already in that
     * script are already in the language. Not so for Cyrillic, Han or Latin,
     * which many languages share.
     */
    private val SCRIPT_IS_LANGUAGE = setOf("ko", "th")

    /** Told when a song translated ahead is played with lyrics that answer does not cover, and is asked again. */
    @Volatile
    var aheadMismatch: ((trackId: String) -> Unit)? = null

    /** One song's request in a batch, and what filing its answer takes once it is back. */
    @Serializable
    data class AheadItem(
        val customId: String,
        val trackId: String,
        /** A pronunciation ([claudeReading]) rather than a translation. */
        val reading: Boolean,
        val cacheKey: String,
        val aheadKey: String,
        val target: String,
        val model: String,
        val keyed: Boolean,
        /** Asked as the numbered array ([AnswerShape.INDEXED]); false for a batch sent before it. */
        val indexed: Boolean = false,
        /** The lines as sent. Kept on the device until the answer is filed, never logged. */
        val sources: List<String>,
        /** The style a translation was asked in, for [storedAnswers]; empty for a pronunciation. */
        val speech: String = "",
        val mode: String = "",
        /** The song's name, when its background is looked up in this same request; see [SongBackgrounds]. */
        val researchTitle: String? = null,
        val researchArtist: String? = null,
        /** KO: furigana in this script ([Furigana.Script.tag]) rather than a translation; empty for none. */
        val furigana: String = "",
        /** KO: the places in [sources] furigana was asked for — the lines with kanji; empty for every line. */
        val furiganaLines: List<Int> = emptyList(),
    )

    enum class AheadSkip { NOTHING_TO_TRANSLATE, ALREADY_TRANSLATED, SAME_LANGUAGE }

    /** Either a request to put in a batch ([item] and [params]), or why this song needs none ([skip]). */
    class AheadRequest(val item: AheadItem?, val params: JsonObject?, val skip: AheadSkip?)

    /**
     * The request that would translate [lines] (or write how they sound, when
     * [reading]) in the style Settings has now — or the reason none is needed.
     */
    suspend fun aheadRequest(
        customId: String,
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        model: String,
        reading: Boolean,
        /**
         * The song, when its background is to be looked up in the same request
         * — a web search before the translation, its answer filed by
         * [SongBackgrounds]. Null for none; never for a pronunciation.
         */
        research: SongBackgrounds.SongName? = null,
        /** KO: furigana in this script instead of [reading] or a translation; [targetLanguageTag] is then unused. */
        furigana: Furigana.Script? = null,
    ): AheadRequest {
        val target = furigana?.tag ?: targetLanguageTag.trim()
        val slots = if (target.isBlank()) emptyList() else flatten(lines)
        if (slots.isEmpty()) return AheadRequest(null, null, AheadSkip.NOTHING_TO_TRANSLATE)
        if (furigana != null) {
            if (!Furigana.wanted(slots.map { it.text })) return AheadRequest(null, null, AheadSkip.SAME_LANGUAGE)
        } else if (alreadyInScriptOf(target, slots) && (reading || canonicalLanguage(target) in SCRIPT_IS_LANGUAGE)) {
            return AheadRequest(null, null, AheadSkip.SAME_LANGUAGE)
        }
        val prefix = when {
            furigana != null -> "furigana\u0000$trackId"
            reading -> "reading\u0000$trackId"
            else -> "claude\u0000$translationSpeech\u0000$translationMode\u0000$trackId"
        }
        val key = cacheKey(prefix, target, slots)
        val cached = readCache(key)
        if (cached != null && cached.texts.size == slots.size &&
            repeatedAcrossLines(cached.texts, slots.map { it.text }) == null
        ) {
            return AheadRequest(null, null, AheadSkip.ALREADY_TRANSLATED)
        }
        if (fromAhead(trackId, prefix, target, slots, report = false) != null) {
            return AheadRequest(null, null, AheadSkip.ALREADY_TRANSLATED)
        }
        // A batch has no second try. The player asks with one key per line
        // first and, when the API refuses that schema as an invalid request
        // (as it does for a song of ordinary length), asks again with the
        // numbered array; a batch sent with the per-line schema came back
        // refused for every song. So a batch asks the way the player's request
        // succeeds: the numbered array, where a line split in two is joined back.
        val shape = AnswerShape.INDEXED
        val lookUp = research?.takeIf { !reading && furigana == null }
        // KO: furigana is asked for the lines with kanji only; see [claudeFurigana].
        val furiganaLines = if (furigana != null) furiganaLinesOf(slots) else emptyList()
        if (furigana != null && furiganaLines.isEmpty()) return AheadRequest(null, null, AheadSkip.SAME_LANGUAGE)
        val sentSlots = if (furigana != null) furiganaLines.map { slots[it] } else slots
        val params = claudeBody(
            model = model,
            system = if (furigana != null) {
                furiganaInstruction(furigana, shape)
            } else if (reading) {
                readingInstruction(target, slots.size, shape)
            } else {
                translationInstruction(target, slots.size, shape) + (lookUp?.let { SongBackgrounds.batchInstruction(target) } ?: "")
            },
            input = translationInput(sentSlots, target, trackId),
            schema = schemaFor(shape, sentSlots.size).let { if (lookUp != null) withBackgroundField(it) else it },
            effort = if (furigana != null) furiganaEffort else "medium",
            tools = lookUp?.let { SongBackgrounds.searchTools(SongBackgrounds.batchDomains) },
            cache = true,
        )
        val item = AheadItem(
            customId = customId,
            trackId = trackId,
            reading = reading,
            cacheKey = key,
            aheadKey = aheadKey(prefix, target),
            target = target,
            model = model,
            keyed = false,
            indexed = true,
            sources = slots.map { it.text },
            speech = if (reading || furigana != null) "" else translationSpeech,
            mode = if (reading || furigana != null) "" else translationMode,
            researchTitle = lookUp?.title,
            researchArtist = lookUp?.artist,
            furigana = furigana?.tag.orEmpty(),
            furiganaLines = furiganaLines,
        )
        return AheadRequest(item, params, null)
    }

    /** [schema] with the song's background as one more required field, for a request that looks it up too. */
    private fun withBackgroundField(schema: JsonObject): JsonObject = buildJsonObject {
        schema.forEach { (key, value) ->
            when (key) {
                "properties" -> putJsonObject("properties") {
                    (value as JsonObject).forEach { (name, property) -> put(name, property) }
                    put(SongBackgrounds.BATCH_FIELD, SongBackgrounds.batchSchema)
                }
                "required" -> putJsonArray("required") {
                    (value as kotlinx.serialization.json.JsonArray).forEach { add(it) }
                    add(SongBackgrounds.BATCH_FIELD)
                }
                else -> put(key, value)
            }
        }
    }

    private fun aheadKey(prefix: String, target: String): String = cacheKey(AHEAD_PREFIX + prefix, target, emptyList())

    private fun claudePrefix(trackId: String) = "claude\u0000$translationSpeech\u0000$translationMode\u0000$trackId"
    private fun googlePrefix(trackId: String) = "google\u0000$trackId"

    /** A line as it is matched across lyric sources: the same words, whatever the spacing. */
    fun lineKey(text: String): String = text.trim().replace(WHITESPACE, " ")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Bumped whenever a song's line-by-line translation is written, so a
     * screen that shows translations made elsewhere — the floating bar, the
     * lock screen — can look again at once rather than on its next round.
     */
    val translationsChanged = MutableStateFlow(0L)

    /** Line maps written this run, so a translation read again is not written out again too. */
    private val lineMapsWritten = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Files [value]'s answer line by line — each source line beside its
     * translation — under the song alone, not its exact lyrics. Other screens
     * get their lyrics from their own lookup, which can bring a different
     * source, timing or split; this is what lets them still find each line.
     */
    private suspend fun writeLineMap(
        prefix: String,
        target: String,
        slots: List<TextSlot>,
        value: CachedTranslation,
        keep: Boolean,
        onlyOnce: Boolean = false,
    ) {
        if (value.texts.size != slots.size || sameLanguage(value.sourceLanguage, target)) return
        val key = aheadKey(prefix, target)
        if (onlyOnce && !lineMapsWritten.add(key)) return
        lineMapsWritten.add(key)
        writeCache(key, value.copy(texts = slots.map { it.text } + value.texts), keep = keep)
        translationsChanged.value = translationsChanged.value + 1
    }

    /**
     * The song's translation as the player last made or showed it, by line:
     * [lineKey] of each source line to its translation. Claude's in the style
     * Settings has now first, Google's otherwise. Null when there is none, or
     * the lyrics are already in [targetLanguageTag]. Never a request.
     */
    suspend fun lineTranslations(trackId: String, targetLanguageTag: String): Map<String, String>? {
        val target = targetLanguageTag.trim()
        if (target.isBlank()) return null
        val order = if (claudeEnabled) {
            listOf(claudePrefix(trackId), googlePrefix(trackId))
        } else {
            listOf(googlePrefix(trackId), claudePrefix(trackId))
        }
        for (prefix in order) {
            val map = readCache(aheadKey(prefix, target)) ?: continue
            if (sameLanguage(map.sourceLanguage, target)) return null
            val half = map.texts.size / 2
            if (half == 0 || map.texts.size != half * 2) continue
            val result = HashMap<String, String>(half * 2)
            for (index in 0 until half) {
                val translated = map.texts[half + index]
                if (translated.isNotBlank()) result.putIfAbsent(lineKey(map.texts[index]), translated)
            }
            if (result.isNotEmpty()) return result
        }
        return null
    }

    /**
     * An answer for [slots] put together from one translated ahead for other
     * lyrics of the same song: the same lines from another source, timed or
     * split differently. Only when every line here was a line there; otherwise
     * null, and — when [report] — [aheadMismatch] hears of it.
     */
    private suspend fun fromAhead(
        trackId: String,
        prefix: String,
        target: String,
        slots: List<TextSlot>,
        report: Boolean,
    ): CachedTranslation? {
        val ahead = readCache(aheadKey(prefix, target)) ?: return null
        val half = ahead.texts.size / 2
        if (half == 0 || ahead.texts.size != half * 2) return null
        val bySource = HashMap<String, String>(half * 2)
        for (index in 0 until half) bySource.putIfAbsent(lineKey(ahead.texts[index]), ahead.texts[half + index])
        val texts = ArrayList<String>(slots.size)
        for (slot in slots) {
            val text = bySource[lineKey(slot.text)]
            if (text == null) {
                if (report) aheadMismatch?.invoke(trackId)
                return null
            }
            texts += text
        }
        return CachedTranslation(
            sourceLanguage = ahead.sourceLanguage,
            targetLanguage = target,
            texts = texts,
            engine = ENGINE_CLAUDE,
        )
    }

    private val batchClient by lazy {
        Http.client.newBuilder()
            // A batch of a few dozen songs goes up in one body and comes back in
            // one; a slow mobile connection gets plenty of time for either.
            .callTimeout(300, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /** GET [url], or POST [body] to it when there is one. */
    private suspend fun batchHttp(url: String, key: String, body: String? = null, post: Boolean = body != null): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", CLAUDE_API_VERSION)
            .header("Accept", "application/json")
        if (post) {
            builder.post(if (body != null) body.toRequestBody(JSON_MEDIA) else ByteArray(0).toRequestBody(null))
        }
        return batchClient.newCall(builder.build()).awaitResponse()
    }

    private fun batchHttpFailure(code: Int, text: String, key: String): ClaudeFailure = when {
        code == 401 || code == 403 -> {
            rejectKey(key)
            ClaudeFailure.KEY_REJECTED
        }
        code == 400 && text.contains("credit balance", ignoreCase = true) -> {
            rejectKey(key)
            ClaudeFailure.NO_CREDIT
        }
        code == 429 -> ClaudeFailure.RATE_LIMIT
        code == 529 -> ClaudeFailure.OVERLOADED
        code >= 500 -> ClaudeFailure.SERVER_ERROR
        code in 400..499 -> ClaudeFailure.BAD_REQUEST
        else -> ClaudeFailure.ERROR
    }

    /** The batch's id, or why it was not taken. */
    class AheadSubmitted(val batchId: String?, val failure: ClaudeFailure?, val detail: String?)

    /** Sends [requests] — custom id and Messages API body, from [aheadRequest] — as one batch. */
    suspend fun submitAhead(requests: List<Pair<String, JsonObject>>, model: String): AheadSubmitted {
        val key = cleanClaudeKey()
        val started = System.nanoTime()
        suspend fun done(failure: ClaudeFailure?, id: String? = null, httpCode: Int? = null, detail: String? = null, sent: Boolean = true): AheadSubmitted {
            ClaudeDiagnostics.record(
                ClaudeDiagnostics.Entry(
                    at = System.currentTimeMillis(),
                    purpose = ClaudeDiagnostics.PURPOSE_BATCH,
                    model = model,
                    failure = failure?.name,
                    httpCode = httpCode,
                    elapsedMs = if (sent) (System.nanoTime() - started) / 1_000_000L else 0L,
                    detail = detail ?: "batch sent: ${requests.size} requests",
                ),
            )
            return AheadSubmitted(id, failure, detail)
        }
        if (requests.isEmpty()) return AheadSubmitted(null, ClaudeFailure.ERROR, "nothing to send")
        if (key.isEmpty()) return AheadSubmitted(null, ClaudeFailure.KEY_REJECTED, null)
        if (key == rejectedClaudeKey) {
            return done(ClaudeFailure.KEY_REJECTED, detail = "not sent: this key was refused earlier", sent = false)
        }
        if (claudeOverBudget()) {
            return done(ClaudeFailure.BUDGET, detail = "not sent: this month's limit is reached", sent = false)
        }
        val body = buildJsonObject {
            putJsonArray("requests") {
                requests.forEach { (id, params) ->
                    add(buildJsonObject {
                        put("custom_id", id)
                        put("params", params)
                    })
                }
            }
        }.toString()
        val (code, text) = try {
            batchHttp(BATCH_ENDPOINT, key, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val failure = if (error is java.io.InterruptedIOException) ClaudeFailure.TIMEOUT else ClaudeFailure.NETWORK
            return done(failure, detail = "${error::class.java.simpleName}: ${error.message}")
        }
        if (code !in 200..299) return done(batchHttpFailure(code, text, key), httpCode = code, detail = apiErrorMessage(text))
        val id = runCatching { json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: return done(ClaudeFailure.BAD_ANSWER, httpCode = code, detail = "no batch id in the answer")
        return done(null, id = id, httpCode = code)
    }

    /** Where a batch stands. [gone]: the API no longer knows it. */
    class AheadStatus(
        val ended: Boolean,
        val gone: Boolean,
        val processing: Int = 0,
        val succeeded: Int = 0,
        val errored: Int = 0,
        val canceled: Int = 0,
        val expired: Int = 0,
        val resultsUrl: String? = null,
    )

    /** Null when it could not be asked just now — offline, a server error — and is worth asking again. */
    suspend fun aheadStatus(batchId: String): AheadStatus? {
        val key = cleanClaudeKey()
        if (key.isEmpty()) return null
        val (code, text) = try {
            batchHttp("$BATCH_ENDPOINT/$batchId", key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return null
        }
        if (code == 404) return AheadStatus(ended = true, gone = true)
        if (code !in 200..299) {
            batchHttpFailure(code, text, key)
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val counts = root["request_counts"] as? JsonObject
            fun count(name: String) = counts?.get(name)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            AheadStatus(
                ended = root["processing_status"]?.jsonPrimitive?.contentOrNull == "ended",
                gone = false,
                processing = count("processing"),
                succeeded = count("succeeded"),
                errored = count("errored"),
                canceled = count("canceled"),
                expired = count("expired"),
                resultsUrl = (root["results_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            )
        }.getOrNull()
    }

    /** Asks for a batch still being worked on to be stopped; what was already answered is still billed. */
    suspend fun cancelAhead(batchId: String): Boolean {
        val key = cleanClaudeKey()
        if (key.isEmpty()) return false
        return try {
            batchHttp("$BATCH_ENDPOINT/$batchId/cancel", key, post = true).first in 200..299
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            false
        }
    }

    /** How one song's request in a batch came out; [failure] null when its answer was filed. */
    class AheadOutcome(
        val customId: String,
        val failure: ClaudeFailure?,
        val detail: String?,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
    )

    /**
     * Reads an ended batch's results and files every usable answer in the kept
     * store, where the player finds it as it would its own. Null when the
     * results could not be fetched just now; an item with no outcome in the
     * list had no line in the results.
     */
    suspend fun collectAhead(batchId: String, resultsUrl: String?, items: List<AheadItem>): List<AheadOutcome>? {
        val key = cleanClaudeKey()
        if (key.isEmpty()) return null
        // The key goes nowhere but the API's own host, whatever the answer named.
        val url = resultsUrl?.takeIf { it.startsWith("https://api.anthropic.com/") } ?: "$BATCH_ENDPOINT/$batchId/results"
        val (code, text) = try {
            batchHttp(url, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return null
        }
        if (code !in 200..299) {
            batchHttpFailure(code, text, key)
            return null
        }
        val byId = items.associateBy { it.customId }
        val outcomes = ArrayList<AheadOutcome>(items.size)
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val id = (row["custom_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
            val item = byId[id] ?: continue
            val result = row["result"] as? JsonObject
            outcomes += when ((result?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull) {
                "succeeded" -> try {
                    fileAhead(item, result?.get("message")?.toString().orEmpty())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AheadOutcome(id, ClaudeFailure.BAD_ANSWER, error.toString())
                }
                "errored" -> {
                    val detail = apiErrorMessage(result?.get("error")?.toString().orEmpty())
                    val failure = if (detail.contains("invalid_request")) ClaudeFailure.BAD_REQUEST else ClaudeFailure.SERVER_ERROR
                    AheadOutcome(id, failure, detail)
                }
                "expired" -> AheadOutcome(id, ClaudeFailure.TIMEOUT, "expired before it was answered")
                "canceled" -> AheadOutcome(id, ClaudeFailure.ERROR, "canceled")
                else -> AheadOutcome(id, ClaudeFailure.BAD_ANSWER, "no result in the line")
            }
        }
        val filed = outcomes.count { it.failure == null }
        val declined = outcomes.count { it.failure == ClaudeFailure.REFUSAL }
        ClaudeDiagnostics.record(
            ClaudeDiagnostics.Entry(
                at = System.currentTimeMillis(),
                purpose = ClaudeDiagnostics.PURPOSE_BATCH,
                model = items.firstOrNull()?.model ?: claudeModel,
                httpCode = code,
                linesAsked = items.size,
                linesGot = filed,
                inputTokens = outcomes.sumOf { it.inputTokens },
                outputTokens = outcomes.sumOf { it.outputTokens },
                // The first error's own words: "failed" alone told nothing of why.
                detail = "batch ended: $filed filed, $declined declined, ${outcomes.size - filed - declined} failed" +
                    (outcomes.firstOrNull { it.failure != null && it.failure != ClaudeFailure.REFUSAL }?.detail
                        ?.let { " · first error: " + it.take(160) } ?: ""),
            ),
        )
        return outcomes
    }

    /** One succeeded request's [message], read as [askClaude] reads an answer and filed under the item's keys. */
    private suspend fun fileAhead(item: AheadItem, message: String): AheadOutcome {
        val meta = responseMeta(message)
        recordUsage(item.model + BATCH_MODEL_SUFFIX, message)
        fun out(failure: ClaudeFailure?, detail: String? = null) =
            AheadOutcome(item.customId, failure, detail, meta.inputTokens ?: 0L, meta.outputTokens ?: 0L)
        when (meta.stopReason) {
            "refusal" -> return out(ClaudeFailure.REFUSAL, meta.refusalCategory)
            "max_tokens" -> return out(ClaudeFailure.CUT_OFF)
        }
        val answer = claudeText(message) ?: return out(ClaudeFailure.BAD_ANSWER, "no text block in the answer")
        // The background looked up in the same request is filed whatever becomes of the translation.
        if (item.researchTitle != null) {
            meta.searches?.takeIf { it > 0 }?.let { searchSink?.invoke(it) }
            runCatching {
                val field = json.parseToJsonElement(answer).jsonObject[SongBackgrounds.BATCH_FIELD]
                SongBackgrounds.fileBatchAnswer(
                    SongBackgrounds.SongName(item.researchTitle, item.researchArtist.orEmpty()),
                    item.target, field, item.sources, meta.searches ?: 0,
                )
            }
        }
        // KO: furigana answers only the lines it was asked for.
        val furiganaScript = Furigana.Script.of(item.furigana)
        val answered = if (furiganaScript != null && item.furiganaLines.isNotEmpty()) {
            item.furiganaLines.mapNotNull { item.sources.getOrNull(it) }
        } else {
            item.sources
        }
        val parsed = try {
            parseTranslation(
                answer,
                answered,
                when {
                    item.keyed -> AnswerShape.KEYED
                    item.indexed -> AnswerShape.INDEXED
                    else -> AnswerShape.ARRAY
                },
            )
        } catch (error: Exception) {
            Parsed<BatchAnswer>(null, ClaudeFailure.BAD_ANSWER, detail = error.toString())
        }
        val value = parsed.value?.takeIf { parsed.failure == null }
            ?: return out(parsed.failure ?: ClaudeFailure.BAD_ANSWER, parsed.detail)
        if (value.translations.size != answered.size) {
            return out(ClaudeFailure.LINE_MISMATCH, "${value.translations.size}/${answered.size}")
        }
        val furigana = furiganaScript
        val texts = if (furigana != null) {
            if (item.furiganaLines.isNotEmpty()) {
                expandFurigana(item.sources, item.furiganaLines, value.translations, furigana)
            } else {
                checkedFurigana(item.sources, value.translations, furigana)
            }
        } else {
            value.translations.mapIndexed { index, text -> text.ifBlank { item.sources[index] } }
        }
        val entry = CachedTranslation(
            sourceLanguage = value.sourceLanguage,
            targetLanguage = item.target,
            texts = texts,
            engine = ENGINE_CLAUDE,
        )
        writeCache(item.cacheKey, entry, keep = true)
        noteStored(
            item.cacheKey, item.trackId,
            when {
                furigana != null -> KIND_FURIGANA
                item.reading -> KIND_READING
                else -> KIND_TRANSLATION
            },
            entry, item.target,
            model = item.model, kept = keepDir != null, via = VIA_AHEAD,
            speech = item.speech.ifBlank { null }, mode = item.mode.ifBlank { null },
        )
        // The lines and their answers side by side, for [fromAhead].
        writeCache(item.aheadKey, entry.copy(texts = item.sources + texts), keep = true)
        translationsChanged.value = translationsChanged.value + 1
        return out(null)
    }


    // ---- What is stored, song by song ---------------------------------------
    //
    // The stored answers are named by a hash of the song, its lines and the
    // style, which no screen can turn back into a song. So every answer
    // written or found is also noted here, under its song: what it is, who
    // wrote it, in which style, and whether it is in the kept store or the
    // cache. No lyrics are noted.

    const val KIND_TRANSLATION = "translation"
    const val KIND_READING = "reading"
    /** KO: readings over the kanji; see [claudeFurigana]. */
    const val KIND_FURIGANA = "furigana"

    /**
     * KO: how hard Claude thinks over furigana; see [claudeFurigana]. "medium":
     * tried at "low" on ten songs, the hiragana held up but the Hangul readings
     * fell apart (300 of 430 lines kept, against 422), for little time saved.
     */
    @Volatile
    var furiganaEffort: String = "medium"
    const val KIND_MEANING = "meaning"
    const val VIA_PLAYER = "player"
    const val VIA_AHEAD = "ahead"
    const val VIA_DOWNLOAD = "download"
    const val VIA_RESTORE = "restore"

    /** One stored answer: a translation, a pronunciation or a song's explanation. */
    @Serializable
    data class StoredAnswer(
        val key: String,
        val trackId: String,
        /** [KIND_TRANSLATION], [KIND_READING] or [KIND_MEANING]. */
        val kind: String,
        /** "claude" or "google". */
        val engine: String,
        /** The Claude model that wrote it, where known. */
        val model: String? = null,
        val target: String,
        /** A Claude translation's register and closeness; null for anything else. */
        val speech: String? = null,
        val mode: String? = null,
        /** In the kept store, which nothing clears; otherwise in the cache, which space can clear. */
        val kept: Boolean = false,
        /** The lyrics were already in [target]: there is nothing to show. */
        val sameLanguage: Boolean = false,
        /** [VIA_PLAYER], [VIA_AHEAD], [VIA_DOWNLOAD] or [VIA_RESTORE]; null when noted only on being read again. */
        val via: String? = null,
        val at: Long = 0,
        val title: String? = null,
        val artist: String? = null,
    ) {
        val byClaude: Boolean get() = engine == ENGINE_CLAUDE
    }

    /** Names a song by its id, for answers noted where only the id is known. Set by the app. */
    @Volatile
    var trackLabel: ((String) -> Pair<String, String>?)? = null

    private const val INDEX_FILE = "lyrics_translation_index_v1.json"
    private val indexSerializer = kotlinx.serialization.builtins.ListSerializer(StoredAnswer.serializer())
    private val indexMutex = Mutex()
    private val index = LinkedHashMap<String, StoredAnswer>()
    private var indexLoaded = false

    private fun indexFile(): File? = (keepDir ?: cacheDir)?.let { File(it, INDEX_FILE) }

    private suspend fun <T> withIndex(block: (MutableMap<String, StoredAnswer>) -> T): T =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                if (!indexLoaded) {
                    indexLoaded = true
                    runCatching {
                        indexFile()?.takeIf { it.isFile }?.readText()
                            ?.let { json.decodeFromString(indexSerializer, it) }
                            ?.forEach { index[it.key] = it }
                    }
                }
                block(index)
            }
        }

    /** Called holding [indexMutex]. */
    private fun saveIndex() {
        val file = indexFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "$INDEX_FILE.tmp")
            temporary.writeText(json.encodeToString(indexSerializer, index.values.toList()))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    /** Notes [value], stored under [key], as [trackId]'s; writes only when something changed. */
    private suspend fun noteStored(
        key: String,
        trackId: String,
        kind: String,
        value: CachedTranslation,
        target: String,
        model: String?,
        kept: Boolean,
        via: String?,
        /** A Claude translation: the style in force now is the one it was asked in. */
        styled: Boolean = false,
        speech: String? = if (styled) translationSpeech else null,
        mode: String? = if (styled) translationMode else null,
    ) {
        val label = trackLabel?.invoke(trackId)
        runCatching {
            withIndex { map ->
                val old = map[key]
                val next = StoredAnswer(
                    key = key,
                    trackId = trackId,
                    kind = kind,
                    engine = value.engine,
                    model = model ?: old?.model,
                    target = target,
                    speech = speech ?: old?.speech,
                    mode = mode ?: old?.mode,
                    kept = kept,
                    sameLanguage = sameLanguage(value.sourceLanguage, target),
                    via = old?.via ?: via,
                    at = old?.at ?: System.currentTimeMillis(),
                    title = label?.first ?: old?.title,
                    artist = label?.second ?: old?.artist,
                )
                if (next != old) {
                    map[key] = next
                    saveIndex()
                }
            }
        }
    }

    private suspend fun noteMeaning(key: String, trackId: String, target: String, title: String, artist: String, model: String?, via: String?) {
        runCatching {
            withIndex { map ->
                val old = map[key]
                val next = StoredAnswer(
                    key = key,
                    trackId = trackId,
                    kind = KIND_MEANING,
                    engine = ENGINE_CLAUDE,
                    model = model ?: old?.model,
                    target = target,
                    kept = true,
                    via = old?.via ?: via,
                    at = old?.at ?: System.currentTimeMillis(),
                    title = title.ifBlank { old?.title.orEmpty() }.ifBlank { null },
                    artist = artist.ifBlank { old?.artist.orEmpty() }.ifBlank { null },
                )
                if (next != old) {
                    map[key] = next
                    saveIndex()
                }
            }
        }
    }

    /** Where [answer] is on disk, or null once it is gone — cleared from the cache, say. */
    private fun storedFile(answer: StoredAnswer): File? {
        if (answer.kind == KIND_MEANING) {
            return (keepDir ?: cacheDir)?.let { File(File(it, MEANING_DIRECTORY), "${answer.key}.json") }?.takeIf { it.isFile }
        }
        return keptFile(answer.key)?.takeIf { it.isFile }
            ?: cacheDir?.let { File(File(it, CACHE_DIRECTORY), "${answer.key}.json.gz") }?.takeIf { it.isFile }
    }

    /**
     * [answers] as they stand on disk: gone ones dropped from the index,
     * moved ones (cache to kept store) corrected, and names filled in where
     * the app now knows them.
     */
    private suspend fun checked(answers: (Map<String, StoredAnswer>) -> List<StoredAnswer>): List<StoredAnswer> =
        withIndex { map ->
            var changed = false
            val result = ArrayList<StoredAnswer>()
            for (answer in answers(map)) {
                if (storedFile(answer) == null) {
                    map.remove(answer.key)
                    changed = true
                    continue
                }
                val kept = answer.kind == KIND_MEANING || keptFile(answer.key)?.isFile == true
                val label = if (answer.title == null) trackLabel?.invoke(answer.trackId) else null
                val fixed = answer.copy(
                    kept = kept,
                    title = answer.title ?: label?.first,
                    artist = answer.artist ?: label?.second,
                )
                if (fixed != answer) {
                    map[answer.key] = fixed
                    changed = true
                }
                result += fixed
            }
            if (changed) saveIndex()
            result
        }

    /**
     * Moves every Claude answer in the cache to the kept store: copied,
     * read back, and only then removed from the cache, so a run cut short
     * loses nothing and the next one carries on. Returns how many moved.
     */
    suspend fun moveClaudeToKept(): Int = withContext(Dispatchers.IO) {
        val from = cacheDir?.let { File(it, CACHE_DIRECTORY) } ?: return@withContext 0
        val to = keepDir?.let { File(it, KEPT_DIRECTORY) } ?: return@withContext 0
        val files = from.listFiles { file -> file.name.endsWith(".json.gz") }.orEmpty()
        var moved = 0
        val keys = ArrayList<String>()
        for (file in files) {
            val value = readTranslationFile(file) ?: continue
            if (value.engine != ENGINE_CLAUDE) continue
            val key = file.name.removeSuffix(".json.gz")
            diskMutex.withLock {
                runCatching {
                    if (!to.exists()) to.mkdirs()
                    val destination = File(to, file.name)
                    if (!destination.isFile) {
                        val temporary = File(to, "$key.tmp")
                        file.copyTo(temporary, overwrite = true)
                        if (readTranslationFile(temporary) == null) {
                            temporary.delete()
                            return@runCatching
                        }
                        if (!temporary.renameTo(destination)) {
                            temporary.copyTo(destination, overwrite = true)
                            temporary.delete()
                        }
                    }
                    if (destination.isFile) {
                        file.delete()
                        keys += key
                        moved++
                    }
                    Unit
                }
            }
        }
        if (moved > 0) diskMutex.withLock { forgetSizes() }
        if (keys.isNotEmpty()) {
            withIndex { map ->
                keys.forEach { key -> map[key]?.let { map[key] = it.copy(kept = true) } }
                saveIndex()
            }
        }
        moved
    }

    /** What clearing the cache took away. */
    class Cleared(val files: Int, val bytes: Long)

    /**
     * Empties the cache — of Google's translations only when [googleOnly],
     * which are free to ask for again; of everything in it otherwise. The kept
     * store and the songs' explanations are never touched.
     */
    suspend fun clearCache(googleOnly: Boolean): Cleared = withContext(Dispatchers.IO) {
        val directory = cacheDir?.let { File(it, CACHE_DIRECTORY) } ?: return@withContext Cleared(0, 0)
        var files = 0
        var bytes = 0L
        diskMutex.withLock {
            forgetSizes()
            directory.listFiles { file -> file.name.endsWith(".json.gz") }.orEmpty().forEach { file ->
                if (googleOnly && readTranslationFile(file)?.engine != ENGINE_GOOGLE) return@forEach
                val size = file.length()
                if (file.delete()) {
                    files++
                    bytes += size
                }
            }
        }
        memory.clear()
        // The index drops the answers that went the next time it is read.
        Cleared(files, bytes)
    }

    /** After something stored was deleted: nothing remembered of it is used again. */
    private fun forgetDeleted() {
        memory.clear()
        meanings.clear()
        lineMapsWritten.clear()
        translationsChanged.value = translationsChanged.value + 1
    }

    private fun deleteAll(directory: File?, suffix: String): Cleared {
        var files = 0
        var bytes = 0L
        directory?.listFiles { file -> file.name.endsWith(suffix) }.orEmpty().forEach { file ->
            val size = file.length()
            if (file.delete()) {
                files++
                bytes += size
            }
        }
        return Cleared(files, bytes)
    }

    /**
     * Empties the kept store: every translation and pronunciation kept there,
     * Claude's paid ones and downloaded songs' included, and the line-by-line
     * copies. The songs' explanations are not touched — see [clearMeanings].
     */
    suspend fun clearKept(): Cleared = withContext(Dispatchers.IO) {
        val cleared = diskMutex.withLock {
            forgetSizes()
            deleteAll(keepDir?.let { File(it, KEPT_DIRECTORY) }, ".json.gz")
        }
        forgetDeleted()
        // The index drops the answers that went the next time it is read.
        cleared
    }

    /** Deletes every song's explanation. */
    suspend fun clearMeanings(): Cleared = withContext(Dispatchers.IO) {
        val cleared = diskMutex.withLock {
            forgetSizes()
            deleteAll((keepDir ?: cacheDir)?.let { File(it, MEANING_DIRECTORY) }, ".json")
        }
        forgetDeleted()
        cleared
    }

    /**
     * Deletes everything stored for one song, wherever it is: its translations
     * and pronunciations in the cache and the kept store, its explanation, and
     * its line-by-line copies in [targets] (in every speech level and mode).
     */
    suspend fun deleteTrack(trackId: String, targets: Collection<String>): Cleared {
        val answers = withIndex { map -> map.values.filter { it.trackId == trackId } }
        val wantedTargets = (targets + answers.map { it.target }).map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val prefixes = buildList {
            add(googlePrefix(trackId))
            add("reading\u0000$trackId")
            for (speech in listOf(SPEECH_AUTO, SPEECH_CASUAL, SPEECH_POLITE)) {
                for (mode in listOf(MODE_NATURAL, MODE_LITERAL)) add("claude\u0000$speech\u0000$mode\u0000$trackId")
            }
        }
        val lineMapKeys = wantedTargets.flatMap { target -> prefixes.map { aheadKey(it, target) } }
        val cleared = withContext(Dispatchers.IO) {
            diskMutex.withLock {
                forgetSizes()
                var files = 0
                var bytes = 0L
                fun drop(file: File?) {
                    if (file == null || !file.isFile) return
                    val size = file.length()
                    if (file.delete()) {
                        files++
                        bytes += size
                    }
                }
                val cache = cacheDir?.let { File(it, CACHE_DIRECTORY) }
                val meaningsDir = (keepDir ?: cacheDir)?.let { File(it, MEANING_DIRECTORY) }
                for (answer in answers) {
                    if (answer.kind == KIND_MEANING) {
                        drop(meaningsDir?.let { File(it, "${answer.key}.json") })
                    } else {
                        drop(keptFile(answer.key))
                        drop(cache?.let { File(it, "${answer.key}.json.gz") })
                    }
                }
                for (key in lineMapKeys) {
                    drop(keptFile(key))
                    drop(cache?.let { File(it, "$key.json.gz") })
                }
                Cleared(files, bytes)
            }
        }
        withIndex { map ->
            if (map.values.removeAll { it.trackId == trackId }) saveIndex()
        }
        forgetDeleted()
        return cleared
    }

    /** One kind of stored answer: how many songs have one, and the space they take. */
    class StorageSlice(val songs: Int, val bytes: Long)

    /**
     * The stored answers by kind, and by where they are. [other] holds what
     * the index cannot place yet: answers from before it, which are noted when
     * read again, and the line-by-line copies kept of answers translated ahead.
     */
    class StorageBreakdown(
        val claude: StorageSlice,
        val google: StorageSlice,
        val reading: StorageSlice,
        val meaning: StorageSlice,
        val other: StorageSlice,
        val keptBytes: Long,
        val cacheBytes: Long,
    )

    suspend fun storageBreakdown(): StorageBreakdown {
        val answers = checked { map -> map.values.toList() }
        return withContext(Dispatchers.IO) {
            val sizes = HashMap<String, Long>()
            fun list(directory: File?, suffix: String) =
                directory?.listFiles { file -> file.name.endsWith(suffix) }?.toList().orEmpty()
            val kept = list(keepDir?.let { File(it, KEPT_DIRECTORY) }, ".json.gz")
            val cache = list(cacheDir?.let { File(it, CACHE_DIRECTORY) }, ".json.gz")
            val meanings = list((keepDir ?: cacheDir)?.let { File(it, MEANING_DIRECTORY) }, ".json")
            (kept + cache).forEach { sizes[it.name.removeSuffix(".json.gz")] = it.length() }
            meanings.forEach { sizes["m:" + it.name.removeSuffix(".json")] = it.length() }
            fun slice(of: List<StoredAnswer>, meaning: Boolean = false): StorageSlice = StorageSlice(
                songs = of.map { it.trackId }.toSet().size,
                bytes = of.sumOf { sizes[if (meaning) "m:" + it.key else it.key] ?: 0L },
            )
            val claude = answers.filter { it.kind == KIND_TRANSLATION && it.byClaude }
            val google = answers.filter { it.kind == KIND_TRANSLATION && !it.byClaude }
            val reading = answers.filter { it.kind == KIND_READING || it.kind == KIND_FURIGANA }
            val meaning = answers.filter { it.kind == KIND_MEANING }
            val placed = answers.map { if (it.kind == KIND_MEANING) "m:" + it.key else it.key }.toSet()
            val otherFiles = sizes.filterKeys { it !in placed }
            StorageBreakdown(
                claude = slice(claude),
                google = slice(google),
                reading = slice(reading),
                meaning = slice(meaning, meaning = true),
                other = StorageSlice(songs = otherFiles.size, bytes = otherFiles.values.sum()),
                keptBytes = kept.sumOf { it.length() },
                cacheBytes = cache.sumOf { it.length() },
            )
        }
    }

    /** Every stored answer this device knows of, newest first. */
    suspend fun storedAnswers(): List<StoredAnswer> =
        checked { map -> map.values.toList() }.sortedByDescending { it.at }

    /** The stored answers of each of [trackIds]; a song with none is left out. */
    suspend fun storedFor(trackIds: Collection<String>): Map<String, List<StoredAnswer>> {
        val wanted = trackIds.toHashSet()
        return checked { map -> map.values.filter { it.trackId in wanted } }.groupBy { it.trackId }
    }

    /** Bytes used by the kept store, the cache's translations, and the songs' explanations. */
    class StorageUse(val keptBytes: Long, val cacheBytes: Long, val meaningBytes: Long)

    suspend fun storageUse(): StorageUse = withContext(Dispatchers.IO) {
        fun size(directory: File?): Long = directory?.listFiles()?.sumOf { it.length() } ?: 0L
        StorageUse(
            keptBytes = size(keepDir?.let { File(it, KEPT_DIRECTORY) }),
            cacheBytes = size(cacheDir?.let { File(it, CACHE_DIRECTORY) }),
            meaningBytes = size((keepDir ?: cacheDir)?.let { File(it, MEANING_DIRECTORY) }),
        )
    }


    // ---- Taking the paid-for answers to another phone ------------------------

    private const val BACKUP_MARKER = "bitchord-ko-translations"
    private const val BACKUP_INDEX = "index.json"
    private val BACKUP_KEY = Regex("^[0-9a-f]{64}$")
    private val BACKUP_ENTRY = Regex("^(kept|cache|meaning|background)/([0-9a-f]{32}(?:[0-9a-f]{32})?\\.json(?:\\.gz)?)$")
    private const val MAX_BACKUP_ENTRY_BYTES = 4 * 1024 * 1024

    /** How a backup went back in: answers [added], and ones [skipped] — already here, or not readable. */
    class Restored(val added: Int, val skipped: Int)

    /** At most [limit] bytes of [input]; null when there are more. */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > limit) return null
        }
        return buffer.toByteArray()
    }

    private fun readTranslationFile(file: File): CachedTranslation? = runCatching {
        GZIPInputStream(FileInputStream(file)).bufferedReader().use { json.decodeFromString<CachedTranslation>(it.readText()) }
    }.getOrNull()

    /**
     * Writes every Claude answer on this device to [output] as one zip: the
     * kept store, Claude's translations in the cache (Google's cost nothing and
     * are left out) and the song explanations. No key and no settings — only
     * the answers, under names that are hashes. Returns how many went in.
     */
    suspend fun backUpTranslations(output: java.io.OutputStream): Int = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            var count = 0
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_MARKER))
                zip.write("1".toByteArray())
                zip.closeEntry()
                fun add(folder: String, file: File) {
                    zip.putNextEntry(java.util.zip.ZipEntry("$folder/${file.name}"))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
                val kept = keepDir?.let { File(it, KEPT_DIRECTORY) }
                    ?.listFiles { file -> file.name.endsWith(".json.gz") }
                    .orEmpty()
                kept.forEach { add("kept", it) }
                val keptNames = kept.mapTo(HashSet()) { it.name }
                cacheDir?.let { File(it, CACHE_DIRECTORY) }
                    ?.listFiles { file -> file.name.endsWith(".json.gz") }
                    .orEmpty()
                    .forEach { file ->
                        if (file.name !in keptNames && readTranslationFile(file)?.engine == ENGINE_CLAUDE) add("cache", file)
                    }
                (keepDir ?: cacheDir)?.let { File(it, MEANING_DIRECTORY) }
                    ?.listFiles { file -> file.name.endsWith(".json") }
                    .orEmpty()
                    .forEach { add("meaning", it) }
                // Each song's background from the web: a search paid for, like the answers.
                (keepDir ?: cacheDir)?.let { File(it, SongBackgrounds.DIRECTORY) }
                    ?.listFiles { file -> file.name.endsWith(".json") }
                    .orEmpty()
                    .forEach { add("background", it) }
                // What each answer is, so the other phone can say which song it belongs to.
                val listing = withIndex { map -> json.encodeToString(indexSerializer, map.values.toList()) }
                zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_INDEX))
                zip.write(listing.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            count
        }
    }

    /**
     * Reads a zip from [backUpTranslations] back in. Everything goes to the
     * kept store, which nothing clears. An answer already on this device is
     * left as it is; a file that is not one of ours is passed over. Null when
     * [input] is not such a backup at all.
     */
    suspend fun restoreTranslations(input: java.io.InputStream): Restored? = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val root = keepDir ?: cacheDir ?: return@withLock null
            var added = 0
            var skipped = 0
            forgetSizes()
            var marked = false
            var listing: List<StoredAnswer> = emptyList()
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == BACKUP_MARKER) {
                        marked = true
                        continue
                    }
                    if (entry.name == BACKUP_INDEX) {
                        listing = readLimited(zip, MAX_BACKUP_ENTRY_BYTES * 4)
                            ?.let { runCatching { json.decodeFromString(indexSerializer, String(it, Charsets.UTF_8)) }.getOrNull() }
                            .orEmpty()
                        continue
                    }
                    // Only names of the shape this app writes: nothing in a zip
                    // from elsewhere gets to choose where it lands.
                    val match = BACKUP_ENTRY.matchEntire(entry.name)
                    if (entry.isDirectory || match == null) continue
                    val folder = match.groupValues[1]
                    val name = match.groupValues[2]
                    // Plain JSON, not a compressed translation: an explanation or a background.
                    val meaning = folder == "meaning" || folder == "background"
                    if (meaning == name.endsWith(".gz")) continue
                    val bytes = readLimited(zip, MAX_BACKUP_ENTRY_BYTES)
                    val directory = File(
                        root,
                        when (folder) {
                            "meaning" -> MEANING_DIRECTORY
                            "background" -> SongBackgrounds.DIRECTORY
                            else -> KEPT_DIRECTORY
                        },
                    )
                    val destination = File(directory, name)
                    val inCache = !meaning && cacheDir?.let { File(File(it, CACHE_DIRECTORY), name).isFile } == true
                    val valid = bytes != null && !destination.isFile && !inCache && runCatching {
                        if (meaning) {
                            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject.isNotEmpty()
                        } else {
                            val text = GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { readLimited(it, MAX_BACKUP_ENTRY_BYTES * 4) }
                            text != null && json.decodeFromString<CachedTranslation>(String(text, Charsets.UTF_8)).version == CACHE_VERSION
                        }
                    }.getOrDefault(false)
                    if (!valid || bytes == null) {
                        skipped++
                        continue
                    }
                    val written = runCatching {
                        if (!directory.exists()) directory.mkdirs()
                        val temporary = File(directory, "$name.tmp")
                        temporary.writeBytes(bytes)
                        if (!temporary.renameTo(destination)) {
                            temporary.copyTo(destination, overwrite = true)
                            temporary.delete()
                        }
                    }.isSuccess
                    if (written) added++ else skipped++
                }
            }
            if (!marked && added == 0 && skipped == 0) {
                null
            } else {
                // Only answers that are now on this device, and only where this device has no word of its own.
                val known = listing.filter { BACKUP_KEY.matches(it.key) }
                if (known.isNotEmpty()) {
                    withIndex { map ->
                        var changed = false
                        known.forEach { answer ->
                            if (answer.key !in map && storedFile(answer) != null) {
                                map[answer.key] = answer.copy(
                                    kept = answer.kind == KIND_MEANING || keptFile(answer.key)?.isFile == true,
                                    via = VIA_RESTORE,
                                )
                                changed = true
                            }
                        }
                        if (changed) saveIndex()
                    }
                }
                Restored(added, skipped)
            }
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
        /** The song's background from a web search, shown apart and marked as such; empty for none. */
        val background: String = "",
        val backgroundSources: List<String> = emptyList(),
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

        @Synchronized fun clear() {
            entries.clear()
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
        // A background looked up since the explanation was written is shown with it too.
        fun withBackground(value: SongMeaning): SongMeaning {
            if (value.background.isNotBlank()) return value
            val found = SongBackgrounds.stored(title, artist)?.takeIf { it.found } ?: return value
            return value.copy(background = found.background, backgroundSources = found.sources)
        }
        meanings.get(key)?.let { return MeaningResult.Ready(withBackground(it)) }
        readStored(MEANING_DIRECTORY, key)
            ?.let { text -> runCatching { json.decodeFromString<SongMeaning>(text) }.getOrNull() }
            ?.let {
                meanings.put(key, it)
                noteMeaning(key, trackId, target, title, artist, model = null, via = null)
                return MeaningResult.Ready(withBackground(it))
            }
        if (!claudeKeyUsable) return MeaningResult.NoKey

        val lyricText = lines
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(MAX_MEANING_LYRIC_CHARS)
        if (lyricText.isBlank()) return MeaningResult.Unavailable

        // The song's background first, where Settings allows looking it up:
        // the explanation is then written knowing what the song was made for.
        val background = runCatching {
            SongBackgrounds.ensure(SongBackgrounds.SongName(title, artist), target, lines.map { it.text })
        }.getOrElse { if (it is CancellationException) throw it else null }
        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
            put("lyrics", lyricText)
            if (background != null) {
                put("background", background.background)
                if (background.notes.isNotEmpty()) {
                    put("phrase_notes", kotlinx.serialization.json.JsonArray(background.notes.map { note -> buildJsonObject { put("phrase", note.phrase); put("meaning", note.meaning) } }))
                }
            }
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
        noteMeaning(key, trackId, target, title, artist, model = claudeModel, via = VIA_PLAYER)
        return MeaningResult.Ready(withBackground(answer))
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
            appendLine("The input may hold \"background\" and \"phrase_notes\" from a web search about the song. Use them where they fit the lyrics, as fan or official views as they say; they can be wrong, and the lyrics come first.")
            appendLine("Plain text only: no markdown, no bullet characters, no emojis.")
            if (listenerInstructionsForMeaning) appendListenerInstructions()
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
        // Claude switched off means no Claude requests, this one included.
        if (!claudeEnabled || !claudeKeyUsable) return NativeNameResult.Unavailable
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
