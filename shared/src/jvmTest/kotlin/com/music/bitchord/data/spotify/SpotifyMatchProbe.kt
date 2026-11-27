package com.music.bitchord.data.spotify

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.ClaudeDiagnostics
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * KO: Spotify tracks matched to YouTube Music the way the app does now (rules,
 * else the search's first row) and with [SpotifyClaudeMatch], for the "Lyrics
 * match check" workflow (`spotify`). Each case is "title|artist|album|seconds|expected",
 * where expected is a piece of the right YouTube Music title, or empty when the
 * song should not be found. Public songs only: titles are printed, never lyrics.
 */
class SpotifyMatchProbe {
    private fun cell(text: String?) = text.orEmpty().replace("|", "/").take(45)

    @Test
    fun probe() {
        val raw = System.getenv("LIVE_SPOTIFY")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (raw.isEmpty()) return
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = key.isNotBlank()
        val out = StringBuilder("# Spotify → YouTube Music match check\n\n")
        out.append("| # | Spotify track | now (rules, else first row) | with Claude | how |\n|---|---|---|---|---|\n")
        var nowRight = 0; var claudeRight = 0; var nowWrong = 0; var claudeWrong = 0; var nowNone = 0; var claudeNone = 0
        runBlocking(Dispatchers.IO) {
            raw.forEachIndexed { n, line ->
                val parts = line.split('|').map { it.trim() }
                val track = SpotifyTrack(
                    id = "probe$n", title = parts[0], artist = parts.getOrElse(1) { "" },
                    album = parts.getOrNull(2)?.ifBlank { null },
                    durationMs = (parts.getOrNull(3)?.toIntOrNull() ?: 0) * 1000, imageUrl = null,
                )
                val expected = parts.getOrNull(4).orEmpty()
                // The right performer too: a cover by someone else has the right title.
                val expectedArtist = parts.getOrNull(5).orEmpty()
                // The same searches for both, so the comparison is of the choosing alone.
                val cache = HashMap<String, List<Song>>()
                val search: suspend (String) -> List<Song> = { q ->
                    cache.getOrPut(q) {
                        YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull()
                            ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
                    }
                }
                val first = search("${track.title} ${track.artist}".trim())
                val target = TrackMatcher.Target(track.title, track.artist, track.durationMs.takeIf { it > 0 }?.div(1000), track.album)
                val now = TrackMatcher.best(first, target) ?: first.firstOrNull()
                val outcome = runCatching { SpotifyClaudeMatch.match(track, useClaude = key.isNotBlank(), search = search) }.getOrNull()
                fun judge(song: Song?): String = when {
                    song == null && expected.isEmpty() -> "✓ none"
                    song == null -> "✗ none"
                    expected.isEmpty() -> "✗ wrong"
                    // "/" separates titles any of which is right: 봄날/Spring Day.
                    expectedArtist.isNotEmpty() &&
                        expectedArtist.split('/').none { song.artist.lowercase(Locale.ROOT).contains(it.trim().lowercase(Locale.ROOT)) } -> "✗ other artist"
                    expected.split('/').any { song.title.lowercase(Locale.ROOT).contains(it.trim().lowercase(Locale.ROOT)) } -> "✓"
                    else -> "✗ wrong"
                }
                fun show(song: Song?) = song?.let { "${cell(it.title)} — ${cell(it.artist)} (${it.durationMillis() / 1000}s)" } ?: "—"
                val a = judge(now); val b = judge(outcome?.song)
                if (a.startsWith("✓")) nowRight++ else if (now == null) nowNone++ else nowWrong++
                if (b.startsWith("✓")) claudeRight++ else if (outcome?.song == null) claudeNone++ else claudeWrong++
                out.append("| ${n + 1} | ${cell(track.title)} — ${cell(track.artist)} (${track.durationMs / 1000}s) → `${cell(expected)}` | $a ${show(now)} | $b ${show(outcome?.song)} | ${outcome?.how} · calls ${outcome?.claudeCalls} |\n")
            }
        }
        out.append("\n**now:** right $nowRight · wrong $nowWrong · none $nowNone   **with Claude:** right $claudeRight · wrong $claudeWrong · none $claudeNone\n")
        val entries = ClaudeDiagnostics.entries.value
        val usd = entries.sumOf { (it.inputTokens ?: 0L) * 0.10 / 1e6 + (it.outputTokens ?: 0L) * 0.50 / 1e6 }
        out.append("\nClaude: ${entries.size} calls, in ${entries.sumOf { it.inputTokens ?: 0L }} / out ${entries.sumOf { it.outputTokens ?: 0L }} tokens, about $")
            .append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        entries.filter { it.failure != null }.forEach { out.append("- failed: ").append(it.failure).append(" ").append(it.detail.orEmpty().take(120)).append('\n') }
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/spotify.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }
}
