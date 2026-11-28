package com.music.bitchord.data.lyrics

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song's name in its own language, for when its Hangul name finds no lyrics.
 *
 * A Korean cover of a Japanese song is uploaded as "나나호시 관현악단 - 발묘",
 * and every lyrics catalogue files that song as "ナナホシ管弦楽団 - 抜錨". So
 * when the Hangul name comes back empty everywhere, [LyricsRepository] asks
 * here for the original and searches again under it:
 *
 *  1. [fromYouTubeMusic] — an anonymous YouTube Music song search for the
 *     Hangul name, which usually lists the original release first. Free.
 *  2. [fromClaude] — only when that turns up nothing usable, and only with a
 *     Claude key set. Costs a request, so it is made once per song.
 *
 * Both answers — including "there is no foreign original" — are kept on disk,
 * so a song pays for each of these once, not once per play.
 */
object OriginalNames {

    data class Name(val title: String, val artist: String)

    /** Whether a name is worth looking up in another language at all. */
    fun worthTrying(title: String, artist: String): Boolean = hasHangul(title) || hasHangul(artist)

    /**
     * The original release's name as YouTube Music lists it, or null when the
     * search finds the song under its Korean name (it *is* Korean) or nothing
     * that fits. A search that failed outright is not remembered.
     */
    suspend fun fromYouTubeMusic(title: String, artist: String, durationMs: Long): Name? {
        val key = key(title, artist)
        entry(key)?.takeIf { it.youtubeChecked }?.let { return it.youtube() }
        val found = try {
            searchYouTubeMusic(title, artist, durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        update(key) { it.copy(youtubeChecked = true, youtubeTitle = found?.title, youtubeArtist = found?.artist) }
        return found
    }

    /** Claude's answer for the original's name; null when it has none or isn't set up. */
    /** When asking Claude for a song's name last failed, by key; not asked again for a day. */
    private val claudeFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CLAUDE_RETRY_MS = 24L * 60L * 60L * 1000L

    suspend fun fromClaude(title: String, artist: String): Name? {
        val key = key(title, artist)
        // An answer kept from before Korean songs' English names were asked for: a found
        // original stands; a "none" is asked once more, under the wider question.
        entry(key)?.takeIf { it.aiChecked && (it.aiTitle != null || it.aiVersion >= AI_VERSION) }?.let { return it.ai() }
        claudeFailedAt[key]?.let { at -> if (System.currentTimeMillis() - at < CLAUDE_RETRY_MS) return null }
        val asked = LyricsTranslation.claudeEnabled && LyricsTranslation.claudeKeyUsable
        return when (val answer = LyricsTranslation.nativeName(title, artist)) {
            is LyricsTranslation.NativeNameResult.Found -> {
                val name = Name(answer.title, answer.artist)
                update(key) { it.copy(aiChecked = true, aiTitle = name.title, aiArtist = name.artist, aiVersion = AI_VERSION) }
                name
            }
            LyricsTranslation.NativeNameResult.NotForeign -> {
                update(key) { it.copy(aiChecked = true, aiTitle = null, aiArtist = null, aiVersion = AI_VERSION) }
                null
            }
            LyricsTranslation.NativeNameResult.Unavailable -> {
                // A request that went out and failed, not one never made, is held off for a day.
                if (asked) claudeFailedAt[key] = System.currentTimeMillis()
                null
            }
        }
    }

    private const val AI_VERSION = 2

    // ---- The same video, in English ---------------------------------------------

    /**
     * KO: whether [fromYouTubeMusicInEnglish] can tell anything new: the app asks YouTube
     * Music in another language than English, so names reach it in that language.
     */
    fun englishWorthTrying(videoId: String): Boolean =
        VIDEO_ID.matches(videoId) && !Innertube.currentLanguage.lowercase().startsWith("en")

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /**
     * KO: the playing video's own names as YouTube Music writes them in English — a Korean
     * phone is told "밤편지 · 아이유(IU)", while the lyrics catalogues file the song as
     * "Through the Night · IU". Asked by video id, so it is this very recording, not a search
     * that could land on another. Null when English says nothing new. Free; kept per video.
     */
    suspend fun fromYouTubeMusicInEnglish(videoId: String, title: String, artist: String): Name? {
        val key = "v\u0000$videoId"
        entry(key)?.takeIf { it.youtubeChecked }?.let { return it.youtube() }
        val song = try {
            InnertubeParser.parseWatchQueue(Innertube.next(videoId, language = "en"))
                .firstOrNull { it.videoId == videoId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val found = song?.let { englishName(it.title, it.artist, title, artist) }
        update(key) { it.copy(youtubeChecked = true, youtubeTitle = found?.title, youtubeArtist = found?.artist) }
        return found
    }

    /**
     * The English names, tidied for a lyrics search: a Hangul aside in brackets goes
     * ("Through the Night (밤편지)" → "Through the Night"), the first artist is kept.
     * Null when they come to the names already tried.
     */
    internal fun englishName(foundTitle: String, foundArtist: String, title: String, artist: String): Name? {
        val tidyTitle = foundTitle
            .replace(Regex("""\s*[(\[（【]([^)\]）】]*)[)\]）】]""")) { if (hasHangul(it.groupValues[1])) "" else it.value }
            .trim()
            .ifEmpty { foundTitle.trim() }
        // The first named artist, as written: TrackMatcher's own primary artist is lower-cased.
        val tidyArtist = foundArtist.split(ARTIST_JOINS).firstOrNull { it.isNotBlank() }?.trim() ?: foundArtist.trim()
        if (tidyTitle.isEmpty()) return null
        if (compact(tidyTitle) == compact(title) && compact(tidyArtist) == compact(artist)) return null
        return Name(tidyTitle, tidyArtist.ifEmpty { artist })
    }

    private val ARTIST_JOINS = Regex("""\s*(,|&|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

    // ---- YouTube Music ---------------------------------------------------------

    /** How far the original's length may stray from what is playing: covers run a little long or short. */
    private const val MAX_DRIFT_SECONDS = 15

    /**
     * Only the top of the list is trusted: a Hangul spelling YouTube Music has
     * no alias for still gets an answer, and further down that answer is
     * "other songs with similar words".
     */
    private const val TOP_ROWS = 2

    private suspend fun searchYouTubeMusic(title: String, artist: String, durationMs: Long): Name? {
        val response = Innertube.search("$title $artist", SearchFilter.SONGS.params)
        val songs = InnertubeParser.parseSearchPage(response, includeVideos = false).rows
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(TOP_ROWS)
        val seconds = (durationMs / 1000).toInt()
        for (song in songs) {
            if (hasHangul(song.title)) {
                // Listed under the Korean name itself: a Korean song (or its
                // Korean release), which the first search already covered.
                if (compact(song.title) == compact(title)) return null
                continue
            }
            // The length is the only check that works across scripts, so a row
            // without one is not taken on trust.
            val length = TrackMatcher.secondsOf(song.durationText) ?: continue
            if (seconds > 0 && abs(length - seconds) > MAX_DRIFT_SECONDS) continue
            val foundArtist = TrackMatcher.primaryArtist(song.artist).ifBlank { song.artist }.trim()
            val foundTitle = song.title.trim()
            if (foundTitle.isEmpty()) continue
            return Name(foundTitle, foundArtist.ifEmpty { artist })
        }
        return null
    }

    // ---- Remembered answers ------------------------------------------------------

    @Serializable
    private data class Entry(
        val youtubeChecked: Boolean = false,
        val youtubeTitle: String? = null,
        val youtubeArtist: String? = null,
        val aiChecked: Boolean = false,
        val aiTitle: String? = null,
        val aiArtist: String? = null,
        /** KO: 2 once Claude was also asked for a Korean song's English name (see [fromClaude]). */
        val aiVersion: Int = 1,
    ) {
        fun youtube(): Name? = youtubeTitle?.let { Name(it, youtubeArtist.orEmpty()) }
        fun ai(): Name? = aiTitle?.let { Name(it, aiArtist.orEmpty()) }
    }

    @Serializable
    private data class Store(val entries: Map<String, Entry> = emptyMap())

    private const val FILE_NAME = "original_names_v1.json"
    private const val MAX_ENTRIES = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Insertion-ordered, oldest first, so the trim drops the longest-unasked songs. */
    private var entries: LinkedHashMap<String, Entry>? = null

    private fun file(): File? = (LyricsTranslation.keepDir ?: LyricsTranslation.cacheDir)?.let { File(it, FILE_NAME) }

    private suspend fun loaded(): LinkedHashMap<String, Entry> {
        entries?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching {
                file()?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<Store>(it).entries }
            }.getOrNull()
        }
        return LinkedHashMap(read.orEmpty()).also { entries = it }
    }

    private suspend fun entry(key: String): Entry? = mutex.withLock { loaded()[key] }

    private suspend fun update(key: String, change: (Entry) -> Entry): Unit = mutex.withLock {
        val map = loaded()
        val next = change(map.remove(key) ?: Entry())
        map[key] = next
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        val snapshot = Store(LinkedHashMap(map))
        // Written under the lock, so two answers landing together can't have
        // the older snapshot overwrite the newer one.
        withContext(Dispatchers.IO) {
            runCatching {
                val target = file() ?: return@runCatching
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$FILE_NAME.tmp")
                temporary.writeText(json.encodeToString(Store.serializer(), snapshot))
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
        }
        Unit
    }

    // ---- Pieces ---------------------------------------------------------------------

    private fun key(title: String, artist: String) = compact(title) + "\u0000" + compact(artist)

    /** Lower case, letters and digits only: "발묘 (Cover)" and "발묘" are one song. */
    private fun compact(text: String): String =
        text.replace(Regex("""[(\[（【].*?[)\]）】]"""), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { text.lowercase().trim() }

    internal fun hasHangul(text: String): Boolean = text.any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL
    }
}
