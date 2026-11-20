package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.lyrics.ClaudeRecommend
import com.music.bitchord.data.model.Song
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Claude's past song suggestions, newest first, up to [MAX_ENTRIES]: kept in
 * one file on this phone, left out of backups and device transfers (see
 * `data_extraction_rules.xml`), and deleted only when the listener asks.
 */
object RecommendHistory {
    private const val FILE = "claude_recommend_history.json"
    private const val MAX_ENTRIES = 50

    @Serializable
    data class Item(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val albumName: String? = null,
        val reason: String = "",
    ) {
        fun song(): Song = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            albumName = albumName,
        )
    }

    @Serializable
    data class Entry(
        val at: Long,
        /** [ClaudeRecommend.BASIS_SONG] and the rest. */
        val basis: String,
        /** What it started from, as shown: a song's title, or "12 songs". */
        val from: String,
        val mood: String = "",
        val items: List<Item>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())
    private val lock = Mutex()
    private var file: File? = null
    private val _entries = MutableStateFlow<List<Entry>?>(null)

    /** Null until read. */
    val entries: StateFlow<List<Entry>?> = _entries.asStateFlow()

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, FILE)
    }

    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock {
            _entries.value ?: read().also { _entries.value = it }
        }
    }

    private fun read(): List<Entry> = runCatching {
        val source = file?.takeIf { it.isFile } ?: return emptyList()
        json.decodeFromString(serializer, source.readText())
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        val target = file ?: return
        val temporary = File(target.parentFile, "$FILE.tmp")
        temporary.writeText(json.encodeToString(serializer, entries))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    suspend fun add(entry: Entry) = withContext(Dispatchers.IO) {
        lock.withLock {
            val updated = (listOf(entry) + (_entries.value ?: read())).take(MAX_ENTRIES)
            write(updated)
            _entries.value = updated
        }
    }

    suspend fun remove(at: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val updated = (_entries.value ?: read()).filterNot { it.at == at }
            write(updated)
            _entries.value = updated
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            file?.delete()
            _entries.value = emptyList()
        }
    }

    fun itemOf(pick: ClaudeRecommend.Pick) = Item(
        videoId = pick.song.videoId,
        title = pick.song.title,
        artist = pick.song.artist,
        thumbnailUrl = pick.song.thumbnailUrl,
        durationText = pick.song.durationText,
        albumName = pick.song.albumName,
        reason = pick.reason,
    )
}
