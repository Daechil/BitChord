package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Songs the listener has said are one song: uploads the station's own rules
 * cannot join without joining two different songs too. Measured on twenty
 * real stations, a title in another script with the same maker and length
 * joined eight pairs and every one was wrong, so this is left to the listener.
 *
 * Each group is kept as the uploads it was made from. Another upload of one of
 * them — the same title under a shared credit — is held to be in the group as
 * well, by [QueueBuilder]'s own rules. Kept in the settings file, so a settings
 * backup carries it.
 */
object SameSongLinks {
    @Serializable
    data class Member(
        val id: String,
        val title: String,
        val artist: String,
        val duration: String? = null,
        val artistId: String? = null,
    ) {
        fun toSong() = Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = duration, artistId = artistId)
    }

    /** At most this many groups: a list the listener keeps by hand, not a catalogue. */
    const val MAX_GROUPS = 500

    /** At most this many uploads in one group. */
    const val MAX_MEMBERS = 12

    val groups = MutableStateFlow<List<List<Member>>>(emptyList())

    /** Told the encoded groups after every change, to keep them. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ListSerializer(Member.serializer()))

    /** Reads what [sink] was given; anything unreadable is no groups. */
    fun load(encoded: String?) {
        val read = encoded?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
        set(read.map { it.distinctBy(Member::id) }.filter { it.size >= 2 }, save = false)
    }

    fun encode(value: List<List<Member>> = groups.value): String = json.encodeToString(serializer, value)

    fun memberOf(song: Song) = Member(song.videoId, song.title, song.artist, song.durationText, song.artistId)

    /**
     * Joins [song] and [others] into one group, with every group any of them
     * was already in. Fewer than two songs between them changes nothing.
     */
    fun link(song: Song, others: List<Song>) {
        val incoming = (listOf(song) + others).map(::memberOf).distinctBy(Member::id)
        if (incoming.size < 2) return
        val ids = incoming.mapTo(HashSet(), Member::id)
        val (touched, rest) = groups.value.partition { group -> group.any { it.id in ids } }
        val merged = (touched.flatten() + incoming).distinctBy(Member::id).take(MAX_MEMBERS)
        set((rest + listOf(merged)).takeLast(MAX_GROUPS))
    }

    /** Takes one upload out of its group; a group left with one is gone. */
    fun remove(id: String) {
        set(groups.value.map { group -> group.filterNot { it.id == id } }.filter { it.size >= 2 })
    }

    /** Takes a whole group away. */
    fun removeGroup(index: Int) {
        set(groups.value.filterIndexed { i, _ -> i != index })
    }

    /** The group holding this upload by id, or null. */
    fun groupOf(id: String): List<Member>? = groups.value.firstOrNull { group -> group.any { it.id == id } }

    private fun set(value: List<List<Member>>, save: Boolean = true) {
        groups.value = value
        QueueBuilder.setLinks(value.map { group -> group.map(Member::toSong) })
        if (save) sink?.invoke(encode(value))
    }
}
