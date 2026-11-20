package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.durationMillis
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test

/**
 * Asks the real lyrics sources about real songs and reports, per source, whether
 * the words it returned are the same song's as the others' — without printing
 * any of the words. Run only by the "Lyrics match check" workflow
 * (`LIVE_LYRICS=1`); a normal test run skips it, as it needs the network.
 *
 * Each song is looked up on YouTube Music first, so the title, artist and
 * length are the ones the app itself would have.
 */
class LiveLyricsCheck {

    private val queries = listOf(
        // Japanese, including several by one artist: the case that went wrong.
        "ピースサイン Kenshi Yonezu", "Peace Sign Kenshi Yonezu", "Lemon 米津玄師", "KICK BACK 米津玄師", "馬と鹿 米津玄師", "感電 米津玄師",
        "アイドル YOASOBI", "夜に駆ける YOASOBI", "Pretender Official髭男dism", "Subtitle Official髭男dism",
        "マリーゴールド あいみょん", "紅蓮華 LiSA", "うっせぇわ Ado", "新時代 Ado", "ドライフラワー 優里",
        // Korean.
        "Ditto NewJeans", "Hype Boy NewJeans", "밤양갱 비비", "Love wins all IU", "좋은 날 아이유",
        "Supernova aespa", "봄날 BTS", "Dynamite BTS",
        // English, with versions and a duet.
        "Bohemian Rhapsody Queen", "Blinding Lights The Weeknd", "Shape of You Ed Sheeran",
        "Anti-Hero Taylor Swift", "golden hour JVKE", "Espresso Sabrina Carpenter", "bad guy Billie Eilish",
        "APT. ROSÉ Bruno Mars", "Die With A Smile Lady Gaga Bruno Mars", "Flowers Miley Cyrus",
        // Chinese.
        "晴天 周杰伦", "告白气球 周杰伦", "孤勇者 陈奕迅",
    )

    private data class Wanted(val videoId: String, val title: String, val artist: String, val durationMs: Long, val fromYouTube: Boolean)

    private fun wanted(query: String): Wanted = runBlocking {
        val song = runCatching {
            YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
                .firstNotNullOfOrNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        }.getOrNull()
        if (song != null) {
            Wanted(song.videoId, song.title, song.artist, song.durationMillis(), true)
        } else {
            val words = query.split(' ')
            Wanted("", words.first(), words.drop(1).joinToString(" "), 0, false)
        }
    }

    private val sources: List<Pair<String, suspend (Wanted, String, String, String?) -> List<LyricLine>?>> = listOf(
        "lrc.red" to { w, t, a, isrc -> LrcRed.lyrics(t, a, w.durationMs, isrc) },
        "Bini" to { w, t, a, _ -> BiniLyrics.lyrics(t, a, w.durationMs)?.lines },
        "LyricsPlus" to { w, t, a, isrc -> LyricsPlus.lyrics(t, a, w.durationMs, null, isrc) },
        "BetterLyrics" to { w, t, a, _ -> BetterLyrics.lyrics(t, a, w.durationMs, null) },
        "Unison" to { w, t, a, _ -> Unison.lyrics(t, a, w.durationMs, null) },
        "LRCLIB" to { w, t, a, _ -> LrcLib.lyrics(t, a, w.durationMs) },
        "Musixmatch" to { w, t, a, _ -> Musixmatch.lyrics(t, a, w.durationMs) },
        "PaxSenix" to { w, t, a, _ -> PaxSenix.lyrics(t, a, w.durationMs, null) },
        "KuGou" to { w, t, a, _ -> KuGou.lyrics(t, a, w.durationMs, null) },
        "Megalobiz" to { _, t, a, _ -> Megalobiz.lyrics(t, a) },
        "Genius" to { _, t, a, _ -> Genius.lyrics(t, a) },
        "SimpMusic" to { w, _, _, _ -> if (w.videoId.isBlank()) null else SimpMusicLyrics.lyrics(w.videoId, w.durationMs) },
    )

    /** Letters only, lower case, as character trigrams: the same song's words share most of them, another song's few. */
    private fun grams(lines: List<LyricLine>): Set<String> {
        val text = Normalizer.normalize(lines.joinToString("") { it.text }, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return (0..text.length - 3).map { text.substring(it, it + 3) }.toSet()
    }

    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / minOf(a.size, b.size)

    private fun cell(text: String?) = (text ?: "").replace("|", "/").take(40)

    private fun mark(mine: Set<String>?, others: List<Set<String>>): String {
        if (mine == null) return "–"
        if (others.isEmpty()) return "?"
        val best = others.maxOf { similarity(mine, it) }
        return (if (best >= 0.35) "✓" else "✗") + (best * 100).toInt()
    }

    private suspend fun fetchAll(w: Wanted, title: String, artist: String, isrc: String?): List<Set<String>?> =
        kotlinx.coroutines.coroutineScope {
            sources.map { (_, fetch) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(30_000) { runCatching { fetch(w, title, artist, isrc) }.getOrNull() }
                        ?.takeIf { lines -> lines.any { it.text.isNotBlank() } }
                        ?.let(::grams)
                }
            }.awaitAll()
        }

    @Test
    fun liveCheck() {
        if (System.getenv("LIVE_LYRICS") != "1") return
        val report = StringBuilder()
        val names = sources.map { it.first }
        report.append("# Lyrics match check\n\n")
        report.append("Each cell: with the name check / without it. ✓ same words as the other sources · ✗ different words · ? no other source to compare · – nothing.\n")
        report.append("Number = how much of its words the closest other source shares (0–100). `–/✗` = the check stopped a wrong song; `–/✓` = the check dropped a right one.\n\n")
        report.append("| # | YouTube Music | Bini first hit | Bini used | " + names.joinToString(" | ") + " |\n")
        report.append("|" + "---|".repeat(4 + names.size) + "\n")
        var wrongKept = 0
        var wrongStopped = 0
        var rightDropped = 0
        queries.forEachIndexed { index, query ->
            val w = wanted(query)
            val title = w.title.forLyricsSearch()
            val artist = w.artist.artistForLyricsSearch()
            val row = runBlocking(Dispatchers.IO) {
                val raw = runCatching { BiniLyrics.search(title, artist, w.durationMs) }.getOrDefault(emptyList())
                val first = raw.firstOrNull()
                val used = runCatching { BiniLyrics.identify(title, artist, w.durationMs) }.getOrNull()
                SongMatch.bypass = false
                val on = fetchAll(w, title, artist, used?.isrc?.takeIf { it.isNotBlank() })
                SongMatch.bypass = true
                val off = try {
                    fetchAll(w, title, artist, first?.isrc?.takeIf { it.isNotBlank() })
                } finally {
                    SongMatch.bypass = false
                }
                val cells = names.indices.map { i ->
                    val othersOn = on.filterIndexed { j, g -> j != i && g != null }.filterNotNull()
                    val reference = othersOn.ifEmpty { off.filterIndexed { j, g -> j != i && g != null }.filterNotNull() }
                    val a = mark(on[i], reference)
                    val b = mark(off[i], reference)
                    if (a.startsWith("✗")) wrongKept++
                    if (a == "–" && b.startsWith("✗")) wrongStopped++
                    if (a == "–" && b.startsWith("✓")) rightDropped++
                    if (a == b) a else "$a/$b"
                }
                val youtube = if (w.fromYouTube) "${w.title} — ${w.artist} (${w.durationMs / 1000}s)" else "(search failed) $query"
                val firstText = first?.let { "${it.trackName} — ${it.artistName} (${it.duration}s)" } ?: "–"
                val usedText = when {
                    first == null -> "–"
                    used == null -> "rejected"
                    used.isrc == first.isrc -> "same"
                    else -> "${used.trackName} — ${used.artistName} (${used.duration}s)"
                }
                "| ${index + 1} | ${cell(youtube)} | ${cell(firstText)} | ${cell(usedText)} | " + cells.joinToString(" | ") + " |"
            }
            report.append(row).append('\n')
            println(row)
        }
        report.append("\nWrong words still shown (✗ with the check): $wrongKept · wrong songs the check stopped: $wrongStopped · right songs the check dropped: $rightDropped\n")
        val out = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/live-lyrics.md")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
