package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.SameSongLinks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SameSongLinksTest {
    private fun song(id: String, title: String, artist: String, length: String? = "3:00") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length)

    // Two songs the rules keep apart: another script, another credit, a second apart.
    private val kana = song("a", "アイドル", "YOASOBI", "3:33")
    private val latin = song("b", "Idol", "Ayase, ikura", "3:34")

    @After
    fun clear() {
        SameSongLinks.load(null)
        QueueBuilder.linkedSink = null
    }

    @Test
    fun `grouped songs are one song`() {
        assertFalse(QueueBuilder.isSameRecording(kana, latin))
        SameSongLinks.link(kana, listOf(latin))
        assertTrue(QueueBuilder.isSameRecording(kana, latin))
        assertTrue(QueueBuilder.isSameRecording(latin, kana))
    }

    @Test
    fun `another upload of a grouped song is in the group too`() {
        SameSongLinks.link(kana, listOf(latin))
        val video = song("c", "アイドル (Official Video)", "YOASOBI", "3:40")
        assertTrue(QueueBuilder.isSameRecording(video, latin))
    }

    @Test
    fun `a station leaves out a grouped song already queued, and says so`() {
        SameSongLinks.link(kana, listOf(latin))
        val told = mutableListOf<String>()
        QueueBuilder.linkedSink = { left, kept, _ -> told += "${left.videoId}<${kept}" }
        val other = song("d", "Racing into the Night", "YOASOBI2", "4:21")
        val out = QueueBuilder.extend(listOf(kana), listOf(latin, other), limit = 10)
        assertEquals(listOf("d"), out.map { it.videoId })
        assertEquals(listOf("b<アイドル"), told)
    }

    @Test
    fun `two groups joined by a shared song become one, and taking songs out breaks it`() {
        val third = song("e", "아이돌", "요아소비", "3:33")
        SameSongLinks.link(kana, listOf(latin))
        SameSongLinks.link(third, listOf(latin))
        assertEquals(1, SameSongLinks.groups.value.size)
        assertEquals(3, SameSongLinks.groups.value.single().size)
        SameSongLinks.remove("b")
        SameSongLinks.remove("e")
        assertTrue(SameSongLinks.groups.value.isEmpty())
        assertFalse(QueueBuilder.isSameRecording(kana, latin))
    }

    @Test
    fun `groups read back from what was kept, and nonsense is no groups`() {
        var kept: String? = null
        SameSongLinks.sink = { kept = it }
        SameSongLinks.link(kana, listOf(latin))
        SameSongLinks.sink = null
        SameSongLinks.load(null)
        assertNull(SameSongLinks.groupOf("a"))
        SameSongLinks.load(kept)
        assertEquals(setOf("a", "b"), SameSongLinks.groupOf("a")?.map { it.id }?.toSet())
        SameSongLinks.load("not json")
        assertTrue(SameSongLinks.groups.value.isEmpty())
    }
}
