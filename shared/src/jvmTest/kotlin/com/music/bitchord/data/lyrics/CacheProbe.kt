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
import kotlin.test.Test

/**
 * Songs translated one after another with the app's own code, on each model,
 * to see the prompt cache at work: whether the second and later requests read
 * the instruction back, and whether every line still comes back. Prints token
 * counts and line counts only. For the "Lyrics match check" workflow
 * (`claude_search: cache`); needs the ANTHROPIC_API_KEY secret.
 */
class CacheProbe {
    @Test
    fun probe() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        val songs = System.getenv("CACHE_PROBE")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (key.isBlank() || songs.isEmpty()) return
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = true
        LyricsTranslation.listenerInstructions = System.getenv("TRANSLATION_INSTRUCTIONS").orEmpty()
        val out = StringBuilder("# Prompt cache check\n\n")
        runBlocking(Dispatchers.IO) {
            val found = songs.mapNotNull { query ->
                val song = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
                    .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                    .firstOrNull() ?: return@mapNotNull null
                val lines = LyricsRepository.lyrics(song.videoId, song.title, song.artist, song.durationMillis())?.lines
                    ?.filter { it.text.isNotBlank() } ?: return@mapNotNull null
                Triple(query, song.videoId, lines)
            }
            out.append("songs with lyrics: ${found.size} of ${songs.size}\n\n")
            val models = System.getenv("CACHE_MODELS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.map { if (it == "haiku") LyricsTranslation.CLAUDE_HAIKU else LyricsTranslation.CLAUDE_SONNET }
                ?: listOf(LyricsTranslation.CLAUDE_SONNET, LyricsTranslation.CLAUDE_HAIKU)
            for (model in models) {
                LyricsTranslation.claudeModel = model
                // A store of its own per model, so nothing is read back from the last one.
                val dir = Files.createTempDirectory("cache").toFile()
                LyricsTranslation.cacheDir = dir
                LyricsTranslation.keepDir = dir
                out.append("## $model\n\n| song | lines | result | in | cache read | cache write | out | s |\n|---|---|---|---|---|---|---|---|\n")
                found.forEach { (query, id, lines) ->
                    val before = ClaudeDiagnostics.entries.value.size
                    val result = LyricsTranslation.translate("$id-$model", lines, "ko")
                    val entry = ClaudeDiagnostics.entries.value.firstOrNull()?.takeIf { ClaudeDiagnostics.entries.value.size > before }
                    val got = (result as? LyricsTranslation.Result.Translated)?.let { "${it.engine} ${it.lines.size}" } ?: result.javaClass.simpleName
                    out.append("| ${query.take(30)} | ${entry?.linesAsked}→${entry?.linesGot} | ${entry?.failure ?: "ok"} · $got | ${entry?.inputTokens} | ${entry?.cacheRead} | ${entry?.cacheWrite} | ${entry?.outputTokens} | ${(entry?.elapsedMs ?: 0) / 1000.0} |\n")
                }
                out.append('\n')
            }
        }
        val usd = ClaudeDiagnostics.entries.value.sumOf {
            val sonnet = it.model.contains("sonnet")
            val (inPrice, outPrice) = if (sonnet) 2.0 to 10.0 else 0.10 to 0.50
            val readPrice = inPrice * if (sonnet) 0.05 else 0.1
            ((it.inputTokens ?: 0L) * inPrice + (it.outputTokens ?: 0L) * outPrice +
                (it.cacheRead ?: 0L) * readPrice + (it.cacheWrite ?: 0L) * inPrice * 1.25) / 1e6
        }
        out.append("Total about $").append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/cache.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }
}
