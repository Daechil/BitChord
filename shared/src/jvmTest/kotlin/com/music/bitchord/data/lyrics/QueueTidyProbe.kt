package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueDuplicates
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * A queue made to hold the same recording twice under other uploads, and
 * other songs that only look alike — another language, a cover, a live take,
 * a remix — asked about with the app's own code. Prints which groups were
 * right, which were wrong and which were missed. For the "Lyrics match check"
 * workflow (`claude_search: tidy`); needs the ANTHROPIC_API_KEY secret.
 */
class QueueTidyProbe {
    private fun song(id: String, title: String, artist: String, length: String, video: Boolean = false) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, isVideo = video)

    @Test
    fun probe() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        if (System.getenv("QUEUE_TIDY_PROBE") != "1" || key.isBlank()) return
        LyricsTranslation.claudeApiKey = key
        LyricsTranslation.claudeEnabled = true
        val first = listOf(
            song("now", "Bling-Bang-Bang-Born", "Creepy Nuts", "2:48"),
            song("a1", "アイドル", "YOASOBI", "3:33"),
            song("t1", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
            song("n1", "夜に駆ける", "YOASOBI", "4:21"),
            song("l1", "Lemon", "米津玄師", "4:16"),
            song("b1", "抜錨", "ナナホシ管弦楽団", "3:24"),
            song("k1", "KING", "Kanaria", "2:09"),
            song("s1", "Shape of You", "Ed Sheeran", "3:54"),
            song("h1", "春を告げる", "yama", "3:16"),
            song("r1", "ロキ", "みきとP", "3:51"),
            // The same recordings again, under other uploads.
            song("a2", "YOASOBI「アイドル」 Official Music Video", "Ayase", "3:47", video = true),
            song("bb2", "Creepy Nuts - Bling‐Bang‐Bang‐Born (Official MV)", "Creepy Nuts", "3:05", video = true),
            song("l2", "米津玄師 MV「Lemon」", "Kenshi Yonezu", "4:36", video = true),
            song("r2", "ロキ / 鏡音リン・みきとP", "みきとP", "3:51"),
            song("a3", "아이돌", "YOASOBI", "3:33"),
            // Other songs that only look alike.
            song("n2", "Racing Into The Night", "YOASOBI", "4:21"),
            song("a4", "Idol (English Version)", "YOASOBI", "3:33"),
            song("b2", "抜錨 (Cover)", "ウォルピスカーター", "3:26"),
            song("l3", "Lemon (Live)", "米津玄師", "4:31"),
            song("h2", "春を告げる (Remix)", "yama", "3:40"),
            song("k2", "エンヴィーベイビー", "Kanaria", "2:09"),
            song("s2", "Shape of You (Acoustic)", "Ed Sheeran", "3:43"),
            song("t2", "テトリス (Piano ver.)", "Hiiragi Magnetite", "2:51"),
        )
        val firstRight = setOf(setOf("a1", "a2", "a3"), setOf("now", "bb2"), setOf("l1", "l2"), setOf("r1", "r2"))
        val second = listOf(
            song("now", "Supernova", "aespa", "2:58"),
            song("ss1", "Super Shy", "NewJeans", "2:34"),
            song("bb1", "밤양갱", "비비 (BIBI)", "2:26"),
            song("kb1", "KICK BACK", "米津玄師", "3:13"),
            song("ad1", "唱", "Ado", "3:09"),
            song("ms1", "怪物", "YOASOBI", "3:25"),
            song("le1", "Lemon", "米津玄師", "4:16"),
            song("ld1", "LOVE DIVE", "IVE", "2:57"),
            song("dy1", "Dynamite", "BTS", "3:19"),
            song("sd1", "Shut Down", "BLACKPINK", "2:55"),
            // The same recordings again, under other uploads.
            song("ss2", "NewJeans (뉴진스) 'Super Shy' Official MV", "HYBE LABELS", "3:20", video = true),
            song("bb2", "Bam Yang Gang", "BIBI", "2:26"),
            song("kb2", "KICK BACK", "Kenshi Yonezu - Topic", "3:13"),
            song("ad2", "【Ado】唱", "Ado", "3:12", video = true),
            song("dy2", "BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS", "3:43", video = true),
            // Other songs that only look alike.
            song("ss3", "Super Shy (Sped Up)", "NewJeans", "2:08"),
            song("bb3", "밤양갱 (Inst.)", "비비 (BIBI)", "2:26"),
            song("kb3", "KICK BACK (TV size)", "米津玄師", "1:30"),
            song("ad3", "唱 / 歌ってみた", "まふまふ", "3:10", video = true),
            song("ms2", "Monster", "YOASOBI", "3:25"),
            song("le2", "Lemon", "N.E.R.D & Rihanna", "3:40"),
            song("ld2", "LOVE DIVE (Japanese ver.)", "IVE", "2:57"),
            song("dy3", "Dynamite (Tropical Remix)", "BTS", "3:17"),
            song("sd2", "Pink Venom", "BLACKPINK", "3:07"),
            song("sd3", "Shut Down (Live at Coachella)", "BLACKPINK", "3:30", video = true),
        )
        val secondRight = setOf(setOf("ss1", "ss2"), setOf("bb1", "bb2"), setOf("kb1", "kb2"), setOf("ad1", "ad2"), setOf("dy1", "dy2"))
        val sets = when (System.getenv("QUEUE_TIDY_SET")) {
            "2" -> listOf(second to secondRight)
            "all" -> listOf(first to firstRight, second to secondRight)
            else -> listOf(first to firstRight)
        }
        val runs = System.getenv("QUEUE_TIDY_RUNS")?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        val out = StringBuilder("# Queue tidy check\n\n")
        sets.forEachIndexed { setIndex, (queue, right) ->
        repeat(runs) { run ->
        out.append("## queue ${setIndex + 1} · run ${run + 1}\n\n")
        val started = System.currentTimeMillis()
        val groups = runBlocking { QueueDuplicates.find(queue, protectedCount = 1, useClaude = true) }
        out.append("- ${(System.currentTimeMillis() - started) / 1000.0}s · ${groups.size} groups\n\n")
        val found = groups.map { group -> (listOf(group.keep) + group.remove).map { queue[it].videoId }.toSet() }
        groups.forEachIndexed { i, group ->
            val ids = found[i]
            val verdict = when {
                ids in right -> "right"
                right.any { it.containsAll(ids) } -> "right but short"
                else -> "WRONG"
            }
            out.append("- $verdict · keep `${queue[group.keep].title}` · out ${group.remove.joinToString { "`${queue[it].title}`" }} · ${if (group.byClaude) "Claude" else "rules"} · ${group.reason}\n")
        }
        val missed = right.filter { want -> found.none { it.containsAll(want) } }
        out.append("\nmissed: ${missed.ifEmpty { listOf("none") }}\n")
        out.append("wrong: ${found.count { ids -> right.none { it.containsAll(ids) } }}\n\n")
        }
        }
        ClaudeDiagnostics.entries.value.forEach { e ->
            out.append("- ${e.purpose} · ${e.model} · ${e.failure ?: "ok"} · in ${e.inputTokens}/out ${e.outputTokens} · ${e.elapsedMs}ms\n")
        }
        val usd = ClaudeDiagnostics.entries.value.sumOf { (it.inputTokens ?: 0L) * 0.10 / 1e6 + (it.outputTokens ?: 0L) * 0.50 / 1e6 }
        out.append("\nTotal about $").append(String.format(Locale.US, "%.4f", usd)).append(" (~").append((usd * 1400).toInt()).append("원)\n")
        val file = File(System.getenv("LIVE_LYRICS_REPORT") ?: "build/queue-tidy.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println(out)
    }
}
