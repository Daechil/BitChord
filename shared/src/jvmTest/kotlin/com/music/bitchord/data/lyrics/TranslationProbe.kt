package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.durationMillis
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * One song translated the way the app does it, then read over by Sonnet, for
 * the "Lyrics match check" workflow (`translate`). Two translations with the
 * app's own code: A with only the song's name (a short tap with no background
 * kept), B after the song's background was looked up (a long press). The
 * report holds scores, line numbers and a few words of each problem: the
 * workflow's log is public, so neither the lyrics nor the translations are
 * written out. Needs the ANTHROPIC_API_KEY secret.
 */
class TranslationProbe {
    private var compare: String? = null
    private var instructionsB: String? = null
    private val judgeModel get() = if (compare != null) "claude-opus-5-5" else LyricsTranslation.CLAUDE_SONNET
    private fun cell(text: Any?) = text.toString().replace("|", "/").replace("\n", " ")

    @Test
    fun probe() {
        // "song @sonnet": both translations with the background, A on Haiku and B
        // on the model named, reviewed blind by Opus rather than by one of the two.
        val raw = System.getenv("TRANSLATION_PROBE")?.trim().orEmpty()
        val query = raw.substringBefore(" @").trim()
        // "song @instructions": both on Haiku with the background, A with
        // TRANSLATION_INSTRUCTIONS and B with TRANSLATION_INSTRUCTIONS_B.
        val suffix = raw.substringAfter(" @", "").trim().lowercase()
        if (suffix == "instructions") instructionsB = System.getenv("TRANSLATION_INSTRUCTIONS_B").orEmpty()
        compare = suffix.takeIf { it.isNotEmpty() && it != "instructions" }?.let {
            when (it) {
                "sonnet" -> LyricsTranslation.CLAUDE_SONNET
                "haiku" -> LyricsTranslation.CLAUDE_HAIKU
                else -> it
            }
        }
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        if (query.isEmpty() || key.isBlank()) return
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = true
        LyricsTranslation.claudeModel = System.getenv("TRANSLATION_MODEL")?.takeIf { it.isNotBlank() } ?: LyricsTranslation.CLAUDE_HAIKU
        LyricsTranslation.listenerInstructions = System.getenv("TRANSLATION_INSTRUCTIONS").orEmpty()
        val dir = Files.createTempDirectory("tr").toFile()
        LyricsTranslation.cacheDir = dir
        LyricsTranslation.keepDir = dir
        SongBackgrounds.widenWhenMissing = true
        val out = StringBuilder("# Translation check\n\n")
        runBlocking(Dispatchers.IO) { runCatching { check(query, out) }.onFailure { out.append("\nerror: ").append(it.javaClass.simpleName).append(": ").append(it.message?.take(200)).append('\n') } }
        out.append("\n## Claude log\n\n")
        val entries = ClaudeDiagnostics.entries.value
        entries.forEach { e ->
            out.append("- ").append(e.purpose).append(" · ").append(e.model).append(" · ").append(e.failure ?: "ok")
                .append(" · HTTP ").append(e.httpCode).append(" · ").append(e.stopReason)
                .append(" · in ").append(e.inputTokens).append("/out ").append(e.outputTokens)
                .append(" · lines ").append(e.linesAsked).append("→").append(e.linesGot)
                .append(" · searches ").append(e.searches).append(" · ").append(e.elapsedMs).append("ms\n")
        }
        val usd = entries.sumOf {
            val (inPrice, outPrice) = when {
                it.model.contains("opus") -> 4.0 to 20.0
                it.model.contains("sonnet") -> 2.0 to 10.0
                else -> 0.10 to 0.50
            }
            (it.inputTokens ?: 0L) * inPrice / 1e6 + (it.outputTokens ?: 0L) * outPrice / 1e6
        } + entries.sumOf { it.searches ?: 0 } * 0.01
        out.append("\nTotal about $").append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/translation.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }

    private suspend fun check(query: String, out: StringBuilder) {
        val pick = query.substringAfterLast('#', "").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val asked = if (query.contains('#')) query.substringBeforeLast('#').trim() else query
        val songs = YtMusicRepository.search(asked, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        out.append("YouTube Music lists: ")
            .append(songs.take(5).joinToString(" · ") { "`${cell(it.title)}` — `${cell(it.artist)}`" }).append("\n\n")
        val song = songs.getOrNull(pick - 1) ?: run { out.append("no song\n"); return }
        val ms = song.durationMillis()
        out.append("- song: `${song.title}` — `${song.artist}` · ${ms / 1000}s · ${song.videoId}\n")
        val found = LyricsRepository.lyrics(song.videoId, song.title, song.artist, ms)
        if (found == null) { out.append("- no lyrics\n"); return }
        val lines = found.lines.filter { it.text.isNotBlank() }
        out.append("- lyrics: ${found.source.label}, ${lines.size} lines\n")
        out.append("- model: ${LyricsTranslation.claudeModel} · own instructions: ${if (LyricsTranslation.listenerInstructions.isBlank()) "none" else "yes"}\n\n")

        val name = SongBackgrounds.SongName(song.title.forLyricsSearch(), song.artist.artistForLyricsSearch())
        SongBackgrounds.noteSong(song.videoId, song.title, song.artist)

        val a: LyricsTranslation.Result
        val b: LyricsTranslation.Result
        val background: SongBackgrounds.Background?
        val other = compare
        val newInstructions = instructionsB
        if (newInstructions != null) {
            SongBackgrounds.enabled = true
            background = SongBackgrounds.ensure(name, "ko", lines.map { it.text })
            a = LyricsTranslation.translate(song.videoId, lines, "ko")
            out.append("- A (own instructions as they are): ${describe(a)}\n")
            LyricsTranslation.listenerInstructions = newInstructions
            b = LyricsTranslation.translate(song.videoId, lines, "ko", fresh = true)
            out.append("- B (new instructions): ${describe(b)}\n\n")
        } else if (other == null) {
            // A: what a short tap sends when no background is kept: the song's name only.
            SongBackgrounds.enabled = false
            a = LyricsTranslation.translate(song.videoId, lines, "ko")
            out.append("- A (name only): ${describe(a)}\n")
            // B: a long press with the setting on: the background first, then a fresh translation.
            SongBackgrounds.enabled = true
            background = SongBackgrounds.ensure(name, "ko", lines.map { it.text })
            b = LyricsTranslation.translate(song.videoId, lines, "ko", fresh = true)
            out.append("- B (with background): ${describe(b)}\n\n")
        } else {
            SongBackgrounds.enabled = true
            background = SongBackgrounds.ensure(name, "ko", lines.map { it.text })
            a = LyricsTranslation.translate(song.videoId, lines, "ko")
            out.append("- A (${LyricsTranslation.claudeModel}, with background): ${describe(a)}\n")
            LyricsTranslation.claudeModel = other
            b = LyricsTranslation.translate(song.videoId, lines, "ko", fresh = true)
            out.append("- B ($other, with background): ${describe(b)}\n\n")
        }
        if (background != null) {
            out.append("### background (${background.confidence} · ${background.sources.joinToString()})\n\n")
            out.append(background.background).append("\n\n")
            background.notes.forEach { out.append("- ").append(it.phrase).append(": ").append(it.meaning).append('\n') }
            out.append('\n')
        } else {
            out.append("### background: none found\n\n")
        }

        val aLines = (a as? LyricsTranslation.Result.Translated)?.lines?.map { it.text }
        val bLines = (b as? LyricsTranslation.Result.Translated)?.lines?.map { it.text }
        if (aLines == null || bLines == null) { out.append("no translation to review\n"); return }
        judge(song.title, song.artist, lines.map { it.text }, aLines, bLines, background, out)
    }

    private fun describe(result: LyricsTranslation.Result): String = when (result) {
        is LyricsTranslation.Result.Translated -> "${result.engine} · from ${result.sourceLanguage} · ${result.lines.size} lines" +
            (if (result.claudeFellBack) " · Claude fell back (${result.claudeFailure})" else "") +
            (if (result.sonnetStandIn) " · Sonnet stand-in" else "")
        else -> result.toString().take(80)
    }

    private suspend fun judge(
        title: String,
        artist: String,
        source: List<String>,
        a: List<String>,
        b: List<String>,
        background: SongBackgrounds.Background?,
        out: StringBuilder,
    ) {
        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
            background?.let { bg ->
                put("background", bg.background)
                putJsonArray("phrase_notes") { bg.notes.forEach { add(buildJsonObject { put("phrase", it.phrase); put("meaning", it.meaning) }) } }
            }
            putJsonArray("lines") {
                source.indices.forEach { i ->
                    add(buildJsonObject {
                        put("i", i)
                        put("source", source[i])
                        put("a", a.getOrElse(i) { "" })
                        put("b", b.getOrElse(i) { "" })
                    })
                }
            }
        }.toString()
        val system = buildString {
            appendLine("You are a strict reviewer of Korean song-lyric translations, fluent in the source language and in Korean.")
            if (compare == null && instructionsB == null) {
                appendLine("Each line has the source lyric and two Korean translations, A and B. B was written with the song's background and phrase notes in hand; A without.")
            } else {
                appendLine("Each line has the source lyric and two Korean translations, A and B, written independently with the same background and phrase notes in hand. Judge them on their merits alone.")
            }
            appendLine("Use what you know about this song, and the background given, to judge meaning, imagery, wordplay and tone.")
            appendLine("Write every comment in Korean, in 해요체.")
            appendLine("- \"score_a\", \"score_b\": 1 to 10 for the whole song (10 = a fluent Korean speaker would understand the song exactly as a native listener does).")
            appendLine(if (compare == null && instructionsB == null) "- \"summary\": 3 to 5 sentences: how good the translations are, and whether B's background made it better or worse." else "- \"summary\": 3 to 5 sentences: how good each translation is, and which reads better and why.")
            appendLine("- \"title_meaning\": what the title means and whether the translations carried that sense.")
            appendLine("- \"issues\": up to 15 real problems, most serious first: mistranslations, lost wordplay or double meaning, wrong subject, wrong tone, unnatural Korean, words added that are not in the source. Skip matters of taste.")
            appendLine("  Each issue: \"line\" (the i), \"which\" (\"A\", \"B\" or \"both\"), \"kind\", \"fragment\" (at most 8 characters of the source, never a whole line), \"problem\", and \"better\" (at most 15 Korean characters).")
            appendLine("Never copy a whole source line or a whole translated line into your answer.")
        }
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("score_a") { put("type", "integer") }
                putJsonObject("score_b") { put("type", "integer") }
                putJsonObject("summary") { put("type", "string") }
                putJsonObject("title_meaning") { put("type", "string") }
                putJsonObject("issues") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("line") { put("type", "integer") }
                            putJsonObject("which") { put("type", "string") }
                            putJsonObject("kind") { put("type", "string") }
                            putJsonObject("fragment") { put("type", "string") }
                            putJsonObject("problem") { put("type", "string") }
                            putJsonObject("better") { put("type", "string") }
                        }
                        putJsonArray("required") { listOf("line", "which", "kind", "fragment", "problem", "better").forEach { add(it) } }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { listOf("score_a", "score_b", "summary", "title_meaning", "issues").forEach { add(it) } }
            put("additionalProperties", false)
        }
        val result = LyricsTranslation.askClaude(
            purpose = "review",
            system = system,
            model = judgeModel,
            input = input,
            schema = schema,
            effort = "high",
            maxTokens = 16_000,
        ) { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?.let { LyricsTranslation.Parsed<JsonObject>(it) }
                ?: LyricsTranslation.Parsed(null, ClaudeFailure.BAD_ANSWER, detail = "review not readable")
        }
        val review = result.value ?: run { out.append("review failed: ${result.failure}\n"); return }
        fun s(key: String) = review[key]?.jsonPrimitive?.content.orEmpty()
        out.append("## Review ($judgeModel)\n\n")
        out.append("- score A: **${s("score_a")}/10** · score B: **${s("score_b")}/10**\n\n")
        out.append(s("summary")).append("\n\n")
        out.append("**title:** ").append(s("title_meaning")).append("\n\n")
        out.append("| line | which | kind | fragment | problem | better |\n|---|---|---|---|---|---|\n")
        review["issues"]?.jsonArray?.forEach { item ->
            val o = item.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            val line = o["line"]?.jsonPrimitive?.int ?: -1
            // Kept short whatever the review sent: the log is public.
            val fragment = f("fragment").take(10).takeUnless { frag -> source.any { it.trim() == frag.trim() && frag.length > 6 } } ?: "…"
            out.append("| ${line + 1} | ${f("which")} | ${cell(f("kind"))} | ${cell(fragment)} | ${cell(f("problem")).take(220)} | ${cell(f("better")).take(20)} |\n")
        }
    }
}
