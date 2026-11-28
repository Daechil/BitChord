package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.durationMillis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test

/**
 * One song, looked at closely: what YouTube Music calls it, what the search
 * sources answer (titles, artists, lengths, codes — never any words), and
 * which answer each check takes or turns away. Run only by the "Lyrics match
 * check" workflow with songs given (`LIVE_PROBE`, separated by `;`).
 */
class LyricsProbe {
    private fun cell(text: Any?) = text.toString().replace("|", "/").take(60)

    @Test
    fun probe() {
        val all = System.getenv("LIVE_PROBE")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        // KO: "@ko-KR" among the entries: search as a phone set to that language does (hl, gl) —
        // where YouTube Music writes a row's type ("노래") in that language.
        all.firstOrNull { it.startsWith("@") }?.removePrefix("@")?.let { tag ->
            com.music.bitchord.data.innertube.Innertube.appLanguage = { tag }
        }
        val queries = all.filterNot { it.startsWith("@") }
        if (queries.isEmpty()) return
        val out = StringBuilder("# Lyrics probe\n\n")
        out.append("locale: ${com.music.bitchord.data.innertube.Innertube.currentLocaleTag}\n\n")
        queries.forEach { query -> runBlocking(Dispatchers.IO) { probeOne(query, out) } }
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/live-lyrics.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }

    private suspend fun probeOne(query: String, out: StringBuilder) {
        out.append("## ").append(query).append("\n\n")
        // KO: "=title|artist|seconds": no YouTube Music search, the sources asked with exactly
        // these names — to compare spellings of one song ("아이유(IU)" / "IU" / "아이유").
        if (query.startsWith("=")) {
            val p = query.removePrefix("=").split('|').map { it.trim() }
            val seconds = p.getOrNull(2)?.toIntOrNull()
            val direct = com.music.bitchord.data.model.Song(
                videoId = "direct",
                title = p.getOrElse(0) { "" },
                artist = p.getOrElse(1) { "" },
                thumbnailUrl = null,
                durationText = seconds?.let { "%d:%02d".format(it / 60, it % 60) },
            )
            return probeSong(direct, out)
        }
        // "query#N": the Nth song YouTube Music lists, for a song uploaded more than once.
        // KO: "query!all": the song as the "All" tab lists it, the way a typed search plays it.
        val fromAll = query.endsWith("!all")
        val q = query.removeSuffix("!all").trim()
        val pick = q.substringAfterLast('#', "").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val asked = if (q.contains('#')) q.substringBeforeLast('#').trim() else q
        val songs = runCatching {
            YtMusicRepository.search(asked, SearchFilter.SONGS).getOrNull().orEmpty()
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        }.getOrDefault(emptyList())
        if (pick == 1 && songs.size > 1) {
            out.append("YouTube Music lists: ")
            out.append(songs.take(6).joinToString(" · ") { "`${cell(it.title)}` — `${cell(it.artist)}` (${it.durationMillis() / 1000}s)" })
            out.append("\n\n")
        }
        // KO: the "All" tab too, the one a typed search shows: its rows start with their type
        // ("노래 • …"), which is where an artist read as "노래" came from.
        val allTab = runCatching {
            YtMusicRepository.search(asked, SearchFilter.ALL).getOrNull().orEmpty()
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        }.getOrDefault(emptyList())
        out.append("All tab: ")
        out.append(allTab.take(6).joinToString(" · ") { "`${cell(it.title)}` — `${cell(it.artist)}`" }.ifEmpty { "nothing" })
        out.append("\n\n")
        val song = (if (fromAll) allTab else songs).getOrNull(pick - 1)
        if (song == null) {
            out.append("YouTube Music search failed\n\n")
            return
        }
        probeSong(song, out)
    }

    private suspend fun probeSong(song: com.music.bitchord.data.model.Song, out: StringBuilder) {
        val title = song.title.forLyricsSearch()
        val artist = song.artist.artistForLyricsSearch()
        val ms = song.durationMillis()
        // KO: the whole lookup the app makes, its second tries under other names included
        // (the same video's English names, the original's, Claude's when a key is set).
        val whole = withTimeoutOrNull(180_000) {
            runCatching { LyricsRepository.lyrics(song.videoId, song.title, song.artist, ms, song.albumName) }.getOrNull()
        }
        fun described(found: LyricsRepository.Result?) = found?.let { r ->
            val timed = r.lines.count { it.timeMs > 0 }
            val worded = r.lines.count { it.words.isNotEmpty() }
            "${r.source} · ${r.lines.size} lines · ${if (timed > 1) "timed" else "untimed"}${if (worded > 0) " · word-timed" else ""}"
        } ?: "none"
        out.append("- whole lookup, as the app: ${described(whole)}\n")
        // KO: the same without the sources keyed on the video (YouTube's own lyrics and
        // transcripts), so the second tries under other names are what is seen.
        val byNameOnly = LyricsSource.entries.toSet() -
            setOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.SIMP_MUSIC)
        val named = withTimeoutOrNull(180_000) {
            runCatching {
                LyricsRepository.lyrics(song.videoId, song.title, song.artist, ms, song.albumName, sources = byNameOnly)
            }.getOrNull()
        }
        out.append("- by name only (other names tried): ${described(named)}\n")
        out.append("- YouTube Music: `${song.title}` — `${song.artist}` · ${ms / 1000.0}s · ${song.videoId}\n")
        out.append("- asked as: `$title` — `$artist`\n\n")

        // lrc.red's own search, every hit, and which one its check takes.
        val url = "https://lrc.red/search.json".toHttpUrl().newBuilder().addQueryParameter("q", "$title $artist".trim()).build()
        val body = runCatching { lyricsGet(url.toString()) }.getOrNull()
        out.append("### lrc.red search (`${cell("$title $artist")}`)\n\n")
        if (body == null) {
            out.append("no answer (network or HTTP error)\n\n")
        } else {
            val hits = runCatching { lyricsJson.decodeFromString<LrcRed.Response>(body) }.getOrNull()?.hits
            if (hits == null) {
                out.append("answer not readable: `${cell(body.take(200))}`\n\n")
            } else {
                out.append("| # | title | artist | s | isrc | same title & artist |\n|---|---|---|---|---|---|\n")
                hits.take(10).forEachIndexed { i, h ->
                    val passes = LrcRed.best(listOf(h), title, artist, ms) != null
                    out.append("| ${i + 1} | ${cell(h.title)} | ${cell(h.artist)} | ${h.duration} | ${h.isrc} | ${if (passes) "yes" else "no"} |\n")
                }
                out.append("\nchosen: ${LrcRed.best(hits, title, artist, ms)?.let { "${it.title} (${it.isrc})" } ?: "none"}\n\n")
            }
        }

        // The same search with the title written other ways: how many hits each gets.
        out.append("### lrc.red search, other spellings\n\n")
        listOf(
            "$title $artist",
            "${title.replace('\'', '\u2019')} $artist",
            "${title.replace("'", "")} $artist",
            "${title.replace("'", " ")} $artist",
            title,
            title.replace("'", ""),
        ).distinct().forEach { q ->
            val u = "https://lrc.red/search.json".toHttpUrl().newBuilder().addQueryParameter("q", q).build()
            val b = runCatching { lyricsGet(u.toString()) }.getOrNull()
            val n = b?.let { runCatching { lyricsJson.decodeFromString<LrcRed.Response>(it) }.getOrNull()?.hits?.size }
            val first = b?.let { runCatching { lyricsJson.decodeFromString<LrcRed.Response>(it) }.getOrNull()?.hits?.firstOrNull() }
            out.append("- `${cell(q)}`: ${n ?: "no answer"} hits${first?.let { " · first: ${cell(it.title)} — ${cell(it.artist)}" } ?: ""}\n")
        }
        out.append('\n')

        // Bini's search and identify, which hands lrc.red an ISRC in the app.
        out.append("### BiniLyrics\n\n")
        val bini = runCatching { BiniLyrics.search(title, artist, ms) }.getOrDefault(emptyList())
        bini.take(6).forEachIndexed { i, h ->
            out.append("- ${i + 1}. ${cell(h.trackName)} — ${cell(h.artistName)} · ${h.duration} · ${h.isrc} · accepted ${BiniLyrics.accepts(h, title, artist, ms)}\n")
        }
        val identified = runCatching { BiniLyrics.identify(title, artist, ms) }.getOrNull()
        out.append("- identify: ${identified?.let { "${it.trackName} (${it.isrc})" } ?: "none"}\n\n")

        // Each source as the app asks it: how many lines came back, no words.
        out.append("### sources (lines found)\n\n")
        val isrc = identified?.isrc?.takeIf { it.isNotBlank() }
        val sources: List<Pair<String, suspend () -> List<LyricLine>?>> = listOf(
            "lrc.red (with Bini's ISRC)" to { LrcRed.lyrics(title, artist, ms, isrc) },
            "lrc.red (search only)" to { LrcRed.lyrics(title, artist, ms, null) },
            "Bini" to { identified?.let { BiniLyrics.lyricsFor(it)?.lines } },
            "BetterLyrics" to { BetterLyrics.lyrics(title, artist, ms, null) },
            "LyricsPlus" to { LyricsPlus.lyrics(title, artist, ms, null, isrc) },
            "PaxSenix" to { PaxSenix.lyrics(title, artist, ms, null) },
            "LRCLIB" to { LrcLib.lyrics(title, artist, ms) },
            "Musixmatch" to { Musixmatch.lyrics(title, artist, ms) },
        )
        sources.forEach { (name, fetch) ->
            val result = withTimeoutOrNull(60_000) { runCatching { fetch() } }
            val text = when {
                result == null -> "timed out"
                result.isFailure -> "error ${result.exceptionOrNull()?.javaClass?.simpleName}"
                else -> result.getOrNull()?.let { lines ->
                    val worded = lines.count { it.words.isNotEmpty() }
                    "${lines.size} lines" + if (worded > 0) " · word-timed" else ""
                } ?: "none"
            }
            out.append("- $name: $text\n")
        }
        out.append('\n')
    }
}
