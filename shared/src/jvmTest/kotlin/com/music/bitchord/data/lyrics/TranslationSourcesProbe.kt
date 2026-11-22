package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.durationMillis
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * Where a person's translation of a song's lyrics into Korean might already
 * be: Musixmatch's listener translations, the translation lines Apple's
 * lyric documents can carry, YouTube's caption tracks, Genius's translation
 * pages, and namu.wiki. Counts, languages and status codes only — the
 * workflow's log is public, so no lyric or translated line is written out.
 * Run by the "Lyrics match check" workflow with `sources_ko`.
 */
class TranslationSourcesProbe {
    private fun cell(text: Any?) = text.toString().replace("|", "/").replace("\n", " ").take(80)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun probe() {
        val songs = System.getenv("TRANSLATION_SOURCES")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (songs.isEmpty()) return
        val out = StringBuilder("# Where Korean lyric translations are\n\n")
        songs.forEach { query -> runBlocking(Dispatchers.IO) { runCatching { check(query, out) }.onFailure { out.append("error: ${it.javaClass.simpleName}: ${it.message?.take(160)}\n\n") } } }
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/translation-sources.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }

    private suspend fun check(query: String, out: StringBuilder) {
        out.append("## ").append(query).append("\n\n")
        val song = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .firstOrNull() ?: run { out.append("- no song on YouTube Music\n\n"); return }
        val ms = song.durationMillis()
        val title = song.title.forLyricsSearch()
        val artist = song.artist.artistForLyricsSearch()
        out.append("- YouTube Music: `${cell(song.title)}` — `${cell(song.artist)}` · ${ms / 1000}s · ${song.videoId}\n")

        // Musixmatch: what its listeners have translated, in Korean and, for scale, in English.
        for (language in listOf("ko", "en")) {
            val found = runCatching { Musixmatch.translationsRaw(title, artist, ms, language) }.getOrNull()
            if (found == null) {
                out.append("- Musixmatch ($language): no matching track\n")
                continue
            }
            val (track, raw) = found
            val root = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val message = root?.get("message") as? JsonObject
            val status = (message?.get("header") as? JsonObject)?.get("status_code")?.jsonPrimitive?.contentOrNull
            val list = ((message?.get("body") as? JsonObject)?.get("translations_list") as? JsonArray)?.size
            out.append("- Musixmatch ($language): `${cell(track)}` · status $status · translated lines ${list ?: "-"}\n")
        }

        // Apple's lyric document through lrc.red: translation spans and the languages they are in.
        val isrc = runCatching { LrcRed.search(title, artist, ms)?.isrc }.getOrNull()
        val doc = isrc?.let(LrcRed::documentUrl)?.let { url -> runCatching { lyricsGet(url) }.getOrNull() }
        if (doc == null) {
            out.append("- Apple TTML (lrc.red): ${if (isrc == null) "no ISRC found" else "no document for $isrc"}\n")
        } else {
            val translationSpans = Regex("""ttm:role="x-translation"""").findAll(doc).count()
            val translationBlocks = Regex("""<translation\b[^>]*xml:lang="([^"]+)"""").findAll(doc).map { it.groupValues[1] }.toList()
            val transliterations = Regex("""<transliteration\b[^>]*xml:lang="([^"]+)"""").findAll(doc).map { it.groupValues[1] }.toList()
            out.append("- Apple TTML (lrc.red, $isrc): ${doc.length} chars · translation spans $translationSpans · translations ${translationBlocks.ifEmpty { listOf("none") }} · transliterations ${transliterations.ifEmpty { listOf("none") }}\n")
        }

        // YouTube's caption tracks for the video: which languages, typed by a person or made by speech recognition.
        val captions = runCatching { captionTracks(song.videoId) }.getOrElse { listOf("error ${it.javaClass.simpleName}") }
        out.append("- YouTube captions (${song.videoId}): ${captions.ifEmpty { listOf("none") }.joinToString()}\n")

        // Genius: translation pages for the song.
        val genius = runCatching {
            lyricsGet("https://genius.com/api/search/multi?q=" + URLEncoder.encode("$title $artist", "UTF-8"))
        }.getOrNull()
        val titles = genius?.let { body ->
            Regex(""""full_title":"([^"]{1,160})"""").findAll(body).map { it.groupValues[1] }.distinct().toList()
        }.orEmpty()
        val translations = titles.filter { it.contains("Translation", true) || it.contains("번역") || it.contains("Korean", true) }
        out.append("- Genius: ${if (genius == null) "no answer" else "${titles.size} results, translation pages: ${translations.size}"}${translations.take(4).joinToString(prefix = " ", separator = " · ") { "`${cell(it)}`" }.takeIf { translations.isNotEmpty() }.orEmpty()}\n")

        // namu.wiki straight from here.
        val namu = status("https://namu.wiki/w/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20"))
        out.append("- namu.wiki direct: HTTP $namu\n\n")
    }

    /** "ko (person)", "ja (speech recognition)", … from the player answer of the WEB client. */
    private fun captionTracks(videoId: String): List<String> {
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20250101.00.00","hl":"ko"}},"videoId":"$videoId"}"""
        val connection = URI("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.outputStream.use { it.write(body.toByteArray()) }
        if (connection.responseCode != 200) return listOf("HTTP ${connection.responseCode}")
        val root = json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        val playability = (root["playabilityStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
        val tracks = ((root["captions"] as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks")?.jsonArray.orEmpty()
        return tracks.map { element ->
            val track = element.jsonObject
            val language = track["languageCode"]?.jsonPrimitive?.contentOrNull
            val kind = track["kind"]?.jsonPrimitive?.contentOrNull
            "$language (${if (kind == "asr") "speech recognition" else "person"})"
        }.ifEmpty { listOf("none · playability $playability") }
    }

    private fun status(url: String): String = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.responseCode.toString()
    }.getOrElse { it.javaClass.simpleName }
}
