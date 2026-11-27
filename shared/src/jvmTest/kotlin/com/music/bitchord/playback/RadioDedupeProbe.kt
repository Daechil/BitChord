package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Real radio stations, looked through for songs [QueueBuilder] now holds to
 * be one: every pair the new rules join, marked where the old rule did not,
 * so a wrong join can be seen before it ships. Names and lengths only. Run
 * by the "Lyrics match check" workflow with seeds given (`LIVE_RADIO`, `;`).
 */
class RadioDedupeProbe {
    private fun cell(text: String) = text.replace("|", "/").take(50)
    private fun describe(s: Song) = "${cell(s.title)} — ${cell(s.artist)} (${s.durationMillis() / 1000}s)"

    // The rule as it was before, kept here to tell new joins from old ones.
    private val oldNoise = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun oldTitle(raw: String) = raw.lowercase(Locale.ROOT).substringBefore(" | ").replace(oldNoise, " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").replace(Regex("""\s+"""), " ").trim()
    private fun oldArtists(raw: String) = raw.lowercase(Locale.ROOT).replace(Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE), " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(Regex("""[^\p{L}\p{N}]+"""), " ").replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }.toSet()
    private fun oldSame(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val t = oldTitle(a.title)
        if (t.isEmpty() || t != oldTitle(b.title)) return false
        val l = oldArtists(a.artist)
        val r = oldArtists(b.artist)
        return l.isEmpty() || r.isEmpty() || l.any { it in r }
    }

    @Test
    fun probe() {
        val seeds = System.getenv("LIVE_RADIO")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (seeds.isEmpty()) return
        val out = StringBuilder("# Radio same-song check\n\nEach station: every pair the new rules hold to be one song. **new** = the old rule kept both.\n\n")
        var newJoins = 0
        var stations = 0
        var songs = 0
        seeds.forEach { query ->
            runBlocking {
                val seed = runCatching {
                    YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
                        .firstNotNullOfOrNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                }.getOrNull()
                if (seed == null) {
                    out.append("## $query\n\nsearch failed\n\n")
                    return@runBlocking
                }
                val radio = YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty().filterNot { it.isVideo }
                stations++
                songs += radio.size
                out.append("## $query → ${describe(seed)} · ${radio.size} songs\n\n")
                val pairs = mutableListOf<String>()
                for (i in radio.indices) for (j in i + 1 until radio.size) {
                    val a = radio[i]
                    val b = radio[j]
                    if (!QueueBuilder.isSameRecording(a, b)) continue
                    val fresh = !oldSame(a, b)
                    if (fresh) newJoins++
                    pairs += "| ${if (fresh) "**new**" else "old"} | ${describe(a)} | ${describe(b)} |"
                }
                val kept = QueueBuilder.extend(listOf(seed), radio, limit = 50)
                val keptOld = radio.filterIndexed { i, s -> radio.take(i).none { oldSame(it, s) } && !oldSame(seed, s) }
                if (pairs.isEmpty()) {
                    out.append("no pairs\n\n")
                } else {
                    out.append("| | one | the other |\n|---|---|---|\n")
                    pairs.forEach { out.append(it).append('\n') }
                    out.append('\n')
                }
                out.append("kept now ${kept.size} (artist caps included) · distinct under the old rule ${keptOld.size}\n\n")
            }
        }
        out.append("---\n$stations stations, $songs songs, $newJoins pairs joined only by the new rules\n")
        out.append("\n").append(freshReport(seeds))
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/radio.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }

    /**
     * KO: how much of a station the listener heard yesterday comes back
     * today, and how much once [FreshRadio] holds it back. "Yesterday" is the
     * first ten songs queued from the seed's station; "today" is radio started
     * again from the seed and from a song in it. Counts only, no names.
     */
    private fun freshReport(seeds: List<String>): String {
        val report = StringBuilder("# Fresh radio check\n\n| seed | again from | heard in plain 10 | heard, moved back | heard, left out |\n|---|---|---|---|---|\n")
        var plainTotal = 0
        var deferTotal = 0
        var dropTotal = 0
        var rows = 0
        seeds.forEachIndexed { n, query ->
            runBlocking {
                val seed = runCatching {
                    YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
                        .firstNotNullOfOrNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                }.getOrNull() ?: return@runBlocking
                val first = YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty()
                val yesterday = QueueBuilder.extend(listOf(seed), first, limit = 10)
                if (yesterday.isEmpty()) return@runBlocking
                val heard = (listOf(seed) + yesterday).mapIndexed { i, song -> FreshRadio.Heard(song, i.toLong()) }
                val isHeard = QueueBuilder.indexAmong(heard.map { it.song })
                for ((label, again) in listOf("seed" to seed, "3rd song" to yesterday.getOrNull(2))) {
                    again ?: continue
                    val station = YtMusicRepository.radio(again.videoId).getOrNull().orEmpty()
                    fun count(order: List<Song>) = QueueBuilder.extend(listOf(again), order, limit = 10).count { isHeard(it) >= 0 }
                    val plain = count(station)
                    val defer = count(FreshRadio.order(station, heard, drop = false).songs)
                    val drop = count(FreshRadio.order(station, heard, drop = true).songs)
                    plainTotal += plain; deferTotal += defer; dropTotal += drop; rows++
                    report.append("| ${n + 1} | $label | $plain | $defer | $drop |\n")
                }
            }
        }
        report.append("\n$rows stations: heard songs in the first 10 — plain $plainTotal, moved back $deferTotal, left out $dropTotal\n")
        return report.toString()
    }
}
