package com.music.bitchord.data.lyrics

import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The app's own song-background lookup against the real Claude API, for the
 * "Lyrics match check" workflow (`claude_search: app`). Needs the
 * ANTHROPIC_API_KEY secret. Prints the summaries, notes and what each request
 * cost: never lyrics, never the key.
 */
class SongBackgroundProbe {
    @Test
    fun probe() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        if (System.getenv("SONG_BACKGROUND_PROBE") != "1" || key.isBlank()) return
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = true
        LyricsTranslation.keepDir = Files.createTempDirectory("bg").toFile()
        SongBackgrounds.enabled = true
        SongBackgrounds.widenWhenMissing = true
        var searches = 0
        LyricsTranslation.searchSink = { searches += it }
        // BACKGROUND_SONGS: "title — artist; title — artist", for other songs than these.
        val songs = System.getenv("BACKGROUND_SONGS")?.takeIf { it.isNotBlank() }?.split(';')
            ?.map { it.split(" — ", " - ", limit = 2) }?.filter { it.size == 2 }
            ?.map { SongBackgrounds.nameFor(it[0].trim(), it[1].trim()) }
            ?: listOf(
                SongBackgrounds.SongName("アイドル", "YOASOBI"),
                SongBackgrounds.SongName("春を告げる", "yama"),
                SongBackgrounds.SongName("テトリス", "柊マグネタイト"),
                SongBackgrounds.SongName("Machine Love", "Jamie Paige"),
                SongBackgrounds.SongName("HOPE", "NF"),
            )
        val out = StringBuilder("# Song background (app code)\n\n")
        // BACKGROUND_MODELS: "haiku,sonnet" to look every song up with each, from nothing each time.
        val models = System.getenv("BACKGROUND_MODELS")?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }
            ?.ifEmpty { null } ?: listOf("haiku")
        runBlocking {
            for (model in models) {
            SongBackgrounds.clearAll()
            SongBackgrounds.researchModel = if (model == "sonnet") LyricsTranslation.CLAUDE_SONNET else LyricsTranslation.CLAUDE_HAIKU
            out.append("# ").append(SongBackgrounds.researchModel).append("\n\n")
            songs.forEach { song ->
                val before = searches
                val started = System.currentTimeMillis()
                val found = runCatching { SongBackgrounds.ensure(song, "ko", emptyList()) }
                out.append("## ").append(song.title).append(" — ").append(song.artist).append(song.sungBy?.let { " (sung by $it)" }.orEmpty()).append("\n\n")
                out.append("- ").append((System.currentTimeMillis() - started) / 1000.0).append("s · searches ").append(searches - before).append('\n')
                found.exceptionOrNull()?.let { out.append("- error: ").append(it.javaClass.simpleName).append(": ").append(it.message?.take(200)).append('\n') }
                val value = found.getOrNull()
                if (value == null) {
                    out.append("- none\n\n")
                } else {
                    out.append("- title in Korean: ").append(value.title.ifBlank { "-" }).append('\n')
                    out.append("- confidence ").append(value.confidence).append(" · sources ").append(value.sources.joinToString()).append("\n\n")
                    out.append(value.background).append("\n\n")
                    value.notes.forEach { out.append("- ").append(it.phrase).append(": ").append(it.meaning).append('\n') }
                    out.append('\n')
                }
            }
            }
        }
        out.append("## Claude log\n\n")
        val entries = ClaudeDiagnostics.entries.value
        entries.forEach { e ->
            out.append("- ").append(e.purpose).append(" · ").append(e.model).append(" · ").append(e.failure ?: "ok")
                .append(" · HTTP ").append(e.httpCode).append(" · ").append(e.stopReason)
                .append(" · in ").append(e.inputTokens).append("/out ").append(e.outputTokens)
                .append(" · searches ").append(e.searches).append(" · ").append(e.elapsedMs).append("ms ")
                .append(e.detail?.take(160).orEmpty()).append('\n')
        }
        val usd = entries.sumOf {
            val (inPrice, outPrice) = if (it.model.contains("sonnet")) 2.0 to 10.0 else 0.10 to 0.50
            (it.inputTokens ?: 0L) * inPrice / 1e6 + (it.outputTokens ?: 0L) * outPrice / 1e6
        } + searches * 0.01
        out.append("\nTotal about $").append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/background.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }
}
