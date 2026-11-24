package com.music.bitchord.data.lyrics

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Why a Claude request gave nothing usable — for the diagnostics log and the lyric status line. */
enum class ClaudeFailure {
    /** Not sent: resting after a rate limit or an overloaded answer. */
    COOLDOWN,
    /** Not sent: this month's spending limit is reached. */
    BUDGET,
    /** Not sent: today's limit for answers asked as they are needed is reached. */
    DAILY_BUDGET,
    /** 401/403, or a key refused earlier and not sent again. */
    KEY_REJECTED,
    /** 400 "credit balance is too low". */
    NO_CREDIT,
    /** 429. */
    RATE_LIMIT,
    /** 529. */
    OVERLOADED,
    /** Any other 5xx. */
    SERVER_ERROR,
    /** Any other 4xx: something about the request itself. */
    BAD_REQUEST,
    /** The call ran past its deadline. */
    TIMEOUT,
    /** No connection, DNS, TLS — the request never got an answer. */
    NETWORK,
    /** stop_reason "refusal": the safety classifiers declined. */
    REFUSAL,
    /** stop_reason "max_tokens": the answer was cut short. */
    CUT_OFF,
    /** A translation came back with a different number of lines than were sent. */
    LINE_MISMATCH,
    /** The answer arrived but could not be read (not JSON, fields missing, every line blank). */
    BAD_ANSWER,
    /** Anything else. */
    ERROR,
}

/**
 * The last [MAX_ENTRIES] Claude requests and how each one went, kept on the
 * device so a failure can be looked at after the fact — a release build logs
 * nothing anywhere else. Holds no key and no lyrics: what was asked, of which
 * model, the HTTP code, the stop reason, timings, line and token counts, and
 * the first part of any error message the API sent back.
 */
object ClaudeDiagnostics {
    const val PURPOSE_TRANSLATE = "translate"
    const val PURPOSE_MEANING = "meaning"
    const val PURPOSE_ORIGINAL_NAME = "original_name"
    const val PURPOSE_READING = "reading"
    /** A batch of songs translated ahead: one entry when it is sent, one when its answers are filed. */
    const val PURPOSE_BATCH = "batch"
    /** Songs Claude suggests to play next. */
    const val PURPOSE_RECOMMEND = "recommend"
    /** A song's background, looked up on the web; see [SongBackgrounds]. */
    const val PURPOSE_BACKGROUND = "background"

    @Serializable
    data class Entry(
        /** When the request finished, epoch millis. */
        val at: Long,
        /** [PURPOSE_TRANSLATE], [PURPOSE_MEANING] or [PURPOSE_ORIGINAL_NAME]. */
        val purpose: String,
        val model: String,
        /** A [ClaudeFailure] name, or null when the answer was used. */
        val failure: String? = null,
        val httpCode: Int? = null,
        val stopReason: String? = null,
        val elapsedMs: Long = 0,
        /** Lines sent to be translated. */
        val linesAsked: Int? = null,
        /** Lines the answer held. */
        val linesGot: Int? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        /** Web searches the request made, each billed apart from the tokens. */
        val searches: Int? = null,
        /** Input read back from the prompt cache, and written to it. */
        val cacheRead: Long? = null,
        val cacheWrite: Long? = null,
        /** The API's error message, an exception, or what was wrong with the answer — cut short. */
        val detail: String? = null,
    ) {
        val failed: Boolean get() = failure != null
        val failureKind: ClaudeFailure? get() = failure?.let { name -> ClaudeFailure.entries.firstOrNull { it.name == name } }
    }

    private const val MAX_ENTRIES = 50
    private const val FILE_NAME = "claude_diagnostics_v1.json"
    private const val MAX_DETAIL_CHARS = 300

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private fun file(): File? =
        (LyricsTranslation.keepDir ?: LyricsTranslation.cacheDir)?.let { File(it, FILE_NAME) }

    /** Reads what was kept from earlier runs, once; later calls do nothing. */
    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    suspend fun record(entry: Entry) {
        val trimmed = entry.copy(detail = entry.detail?.replace('\n', ' ')?.take(MAX_DETAIL_CHARS))
        mutex.withLock {
            ensureLoaded()
            val next = (listOf(trimmed) + _entries.value).take(MAX_ENTRIES)
            _entries.value = next
            save(next)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            loaded = true
            _entries.value = emptyList()
            save(emptyList())
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val read = withContext(Dispatchers.IO) {
            runCatching {
                file()?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(serializer, it) }
            }.getOrNull()
        }
        // Anything recorded before the file was read stays in front of it.
        if (read != null) _entries.value = (_entries.value + read).take(MAX_ENTRIES)
    }

    private suspend fun save(list: List<Entry>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val target = file() ?: return@runCatching
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$FILE_NAME.tmp")
                temporary.writeText(json.encodeToString(serializer, list))
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
        }
    }
}
