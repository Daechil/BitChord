package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The lyrics the player last showed for each song — whichever source it took
 * them from, the one picked by hand included — so translating ahead sends the
 * very words the listener reads, with no search of its own, and finds a song
 * whose lyrics only one source has. Only what translation needs is kept: each
 * line's time and words, and its background vocal.
 */
object PlayedLyrics {
    private const val DIRECTORY = "played_lyrics"
    private const val MAX_SONGS = 500

    @Serializable
    private data class Line(val t: Long, val x: String, val bt: Long? = null, val bx: String? = null)

    @Serializable
    private data class Saved(val videoId: String, val source: String? = null, val lines: List<Line>)

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, DIRECTORY)
    }

    private fun file(videoId: String): File? {
        val base = dir ?: return null
        val name = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(base, "$name.json")
    }

    /** Keeps [lines] as [videoId]'s, replacing what was kept before; written off the caller's thread. */
    fun remember(videoId: String, source: String?, lines: List<LyricLine>) {
        if (videoId.isBlank() || lines.none { it.text.isNotBlank() }) return
        val saved = Saved(
            videoId = videoId,
            source = source,
            lines = lines.map { line ->
                Line(line.timeMs, line.text, line.background?.timeMs, line.background?.text?.takeIf { it.isNotBlank() })
            },
        )
        scope.launch {
            runCatching {
                synchronized(lock) {
                    val target = file(videoId) ?: return@synchronized
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, target.name + ".tmp")
                    temp.writeText(json.encodeToString(Saved.serializer(), saved))
                    if (!temp.renameTo(target)) {
                        target.delete()
                        temp.renameTo(target)
                    }
                    trim()
                }
            }
        }
    }

    /** What the player last showed for [videoId], or null when it has not shown any. */
    suspend fun get(videoId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        runCatching {
            val source = file(videoId)?.takeIf { it.isFile } ?: return@runCatching null
            val saved = json.decodeFromString(Saved.serializer(), source.readText())
            if (saved.videoId != videoId) return@runCatching null
            // Used again: kept longer.
            source.setLastModified(System.currentTimeMillis())
            saved.lines.map { line ->
                LyricLine(
                    timeMs = line.t,
                    text = line.x,
                    background = line.bx?.let { LyricLine(timeMs = line.bt ?: line.t, text = it) },
                )
            }.takeIf { lines -> lines.any { it.text.isNotBlank() } }
        }.getOrNull()
    }

    /** The least recently used songs go once there are more than [MAX_SONGS]. */
    private fun trim() {
        val files = dir?.listFiles { file -> file.name.endsWith(".json") } ?: return
        if (files.size <= MAX_SONGS) return
        files.map { it to it.lastModified() }
            .sortedBy { it.second }
            .take(files.size - MAX_SONGS)
            .forEach { it.first.delete() }
    }
}
