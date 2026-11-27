package com.music.bitchord.data.lyrics

import com.atilika.kuromoji.ipadic.Tokenizer
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.durationMillis
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * KO: real Japanese songs given furigana the way the app asks for it, by
 * Haiku and by Sonnet, for the "Lyrics match check" workflow (`furigana`).
 * Every reading is held against a dictionary (kuromoji, tests only) and
 * against the other model's. The workflow's log is public, so the report holds
 * counts only: no lyrics, no readings. Needs the ANTHROPIC_API_KEY secret.
 */
class FuriganaProbe {
    private val tokenizer by lazy { Tokenizer() }

    private fun hiragana(text: String) = buildString {
        for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    /** The dictionary's reading of [word] on its own, in hiragana, or null where it has none. */
    private fun dictionary(word: String): String? {
        val tokens = tokenizer.tokenize(word)
        if (tokens.isEmpty()) return null
        val readings = tokens.map { token -> token.reading?.takeIf { it != "*" } ?: return null }
        return hiragana(readings.joinToString(""))
    }

    private class Tally {
        var lines = 0
        var kanjiLines = 0
        var readLines = 0
        var kanji = 0
        var kanjiRead = 0
        var readings = 0
        var dictAgree = 0
        var otherAgree = 0
        var neither = 0
        var noDict = 0
        fun add(o: Tally) {
            lines += o.lines; kanjiLines += o.kanjiLines; readLines += o.readLines; kanji += o.kanji; kanjiRead += o.kanjiRead
            readings += o.readings; dictAgree += o.dictAgree; otherAgree += o.otherAgree; neither += o.neither; noDict += o.noDict
        }
        fun row(name: String) = "| $name | $readLines/$kanjiLines | $kanjiRead/$kanji | $readings | $dictAgree | $otherAgree | $neither | $noDict |"
    }

    @Test
    fun probe() {
        val songs = System.getenv("LIVE_FURIGANA")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        if (songs.isEmpty() || key.isBlank()) return
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = true
        LyricsTranslation.sonnetStandInEnabled = false
        val dir = Files.createTempDirectory("furi").toFile()
        LyricsTranslation.cacheDir = dir
        LyricsTranslation.keepDir = dir
        val out = StringBuilder("# Furigana check\n\n")
        out.append("Lines: lines with kanji that got readings. Kanji: kanji under a reading. Of the readings: the same as the dictionary's, ")
            .append("else the same as the other model's (a song reading or a context the dictionary misses), else neither (likely wrong); no dict = the dictionary has none.\n\n")
        out.append("| song · model | lines | kanji | readings | = dictionary | = other model | neither | no dict |\n|---|---|---|---|---|---|---|---|\n")
        val totals = mapOf("haiku" to Tally(), "sonnet" to Tally(), "haiku hangul" to Tally())
        runBlocking(Dispatchers.IO) {
            songs.forEachIndexed { n, query ->
                runCatching { check(n, query, out, totals) }
                    .onFailure { out.append("| ${n + 1} | error: ${it.javaClass.simpleName} |\n") }
            }
        }
        out.append("\n**All songs**\n\n| model | lines | kanji | readings | = dictionary | = other model | neither | no dict |\n|---|---|---|---|---|---|---|---|\n")
        totals.forEach { (name, tally) -> out.append(tally.row(name)).append('\n') }
        out.append("\n## Claude log\n\n")
        val entries = ClaudeDiagnostics.entries.value
        entries.forEach { e ->
            out.append("- ").append(e.purpose).append(" · ").append(e.model).append(" · ").append(e.failure ?: "ok")
                .append(" · in ").append(e.inputTokens).append("/out ").append(e.outputTokens)
                .append(" · cache read ").append(e.cacheRead ?: 0).append(" · lines ").append(e.linesAsked).append("→").append(e.linesGot)
                .append(" · ").append(e.elapsedMs).append("ms\n")
        }
        val usd = entries.sumOf {
            val (inPrice, outPrice) = if (it.model.contains("sonnet")) 2.0 to 10.0 else 0.10 to 0.50
            (it.inputTokens ?: 0L) * inPrice / 1e6 + (it.outputTokens ?: 0L) * outPrice / 1e6
        }
        out.append("\nTotal about $").append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/furigana.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }

    private suspend fun check(n: Int, query: String, out: StringBuilder, totals: Map<String, Tally>) {
        val song = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            .firstNotNullOfOrNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            ?: run { out.append("| ${n + 1} $query | no song |\n"); return }
        val found = LyricsRepository.lyrics(song.videoId, song.title, song.artist, song.durationMillis())
            ?: run { out.append("| ${n + 1} ${song.title} | no lyrics |\n"); return }
        val lines = found.lines.filter { it.text.isNotBlank() }
        // Under ids of their own: answers are held in memory by song.
        LyricsTranslation.claudeModel = LyricsTranslation.CLAUDE_SONNET
        val sonnet = LyricsTranslation.claudeFurigana(song.videoId + "-s", lines, Furigana.Script.HIRAGANA)?.lines
        LyricsTranslation.claudeModel = LyricsTranslation.CLAUDE_HAIKU
        val haiku = LyricsTranslation.claudeFurigana(song.videoId + "-h", lines, Furigana.Script.HIRAGANA)?.lines
        val hangul = LyricsTranslation.claudeFurigana(song.videoId + "-k", lines, Furigana.Script.HANGUL)?.lines
        val name = "${n + 1} ${song.title.replace("|", "/").take(30)}"
        for ((label, mine, other) in listOf(Triple("haiku", haiku, sonnet), Triple("sonnet", sonnet, haiku), Triple("haiku hangul", hangul, null))) {
            if (mine == null) { out.append("| $name · $label | no answer |\n"); continue }
            val tally = tally(lines.map { it.text.trim() }, mine, other, dictionaryCheck = label != "haiku hangul")
            out.append(tally.row("$name · $label")).append('\n')
            totals.getValue(label).add(tally)
        }
    }

    private fun tally(lines: List<String>, mine: List<String?>, other: List<String?>?, dictionaryCheck: Boolean): Tally {
        val t = Tally()
        lines.forEachIndexed { index, line ->
            t.lines++
            val kanji = line.count(Furigana::isKanji)
            if (kanji == 0) return@forEachIndexed
            t.kanjiLines++
            t.kanji += kanji
            val parts = mine.getOrNull(index)?.let(Furigana::segments) ?: return@forEachIndexed
            t.readLines++
            val others = other?.getOrNull(index)?.let(Furigana::segments).orEmpty()
                .filter { it.ruby != null }.associate { it.base to it.ruby }
            for (part in parts) {
                val ruby = part.ruby ?: continue
                t.kanjiRead += part.base.count(Furigana::isKanji)
                t.readings++
                if (!dictionaryCheck) continue
                val dict = dictionary(part.base)
                when {
                    dict == null -> t.noDict++
                    dict == ruby -> t.dictAgree++
                    others[part.base] == ruby -> t.otherAgree++
                    else -> t.neither++
                }
            }
        }
        return t
    }
}
