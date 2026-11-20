package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Line-synced community LRC scraped from Megalobiz. */
object Megalobiz {
    private const val BASE = "https://www.megalobiz.com"

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val search = "$BASE/searchall".toHttpUrl().newBuilder()
            .addQueryParameter("qry", "$artist $title".trim()).build()
        val results = lyricsGet(search.toString()) ?: return@withContext null
        // The first link is whatever the site ranked first; take the first
        // whose address names this song.
        val path = LRC_LINK.findAll(results).map { it.groupValues[1] }
            .firstOrNull { SongMatch.sameTitle(title, slugName(it)) } ?: return@withContext null
        val page = lyricsGet(BASE + path.replace("&amp;", "&")) ?: return@withContext null
        val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: return@withContext null
        val lrc = raw.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .let(EnhancedLrc::decodeEntities)
        LrcLib.parseLrc(lrc).takeIf { lines -> lines.any { it.text.isNotBlank() } }
    }

    /** "/lrc/maker/download/123/Kenshi+Yonezu+-+Peace+Sign" → "Kenshi Yonezu Peace Sign". */
    internal fun slugName(path: String): String =
        runCatching { java.net.URLDecoder.decode(path.substringAfterLast('/').replace("&amp;", "&"), "UTF-8") }
            .getOrDefault(path.substringAfterLast('/'))
            .replace(Regex("""[-_+.]+"""), " ")
            .replace(Regex("""\b\d+\b"""), " ")
            .trim()

    private val LRC_LINK = Regex("""href=[\"'](/lrc/maker/download/[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val LRC_BODY = Regex(
        """id=[\"']lrc_[^\"']*_details[\"'][^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
