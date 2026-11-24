package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueDuplicates
import com.music.bitchord.playback.SameSongLinks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDuplicatesTest {
    private fun song(id: String, title: String, artist: String, length: String = "3:00") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length)

    @After
    fun clear() = SameSongLinks.load(null)

    @Test
    fun `the rules find the same recording, keeping the one playing`() = runBlocking {
        val queue = listOf(
            song("a", "テトリス - Tetoris (feat. Kasane Teto)", "Kasane Teto", "2:47"),
            song("b", "Lemon", "Kenshi Yonezu", "4:16"),
            song("c", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
        )
        val groups = QueueDuplicates.find(queue, protectedCount = 1, useClaude = false)
        assertEquals(1, groups.size)
        assertEquals(0, groups.single().keep)
        assertEquals(listOf(2), groups.single().remove)
    }

    @Test
    fun `without the one playing, the first in line stays`() = runBlocking {
        val queue = listOf(
            song("x", "Lemon", "Kenshi Yonezu", "4:16"),
            song("c", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
            song("a", "テトリス - Tetoris (feat. Kasane Teto)", "Kasane Teto", "2:47"),
        )
        val group = QueueDuplicates.find(queue, protectedCount = 1, useClaude = false).single()
        assertEquals(1, group.keep)
        assertEquals(listOf(2), group.remove)
    }

    @Test
    fun `the listener's own groups count`() = runBlocking {
        val kana = song("k", "アイドル", "YOASOBI", "3:33")
        val video = song("v", "YOASOBI「アイドル」 Official Music Video", "Ayase", "3:47")
        SameSongLinks.link(kana, listOf(video))
        val groups = QueueDuplicates.find(listOf(song("p", "Other", "X"), kana, video), protectedCount = 1, useClaude = false)
        assertEquals(listOf(2), groups.single().remove)
    }

    @Test
    fun `answers that cannot be trusted are dropped`() {
        val checked = QueueDuplicates.checkedPositions(listOf(listOf(0, 1), listOf(1, 2), listOf(3), listOf(4, 9), listOf(5, 5, 6)), size = 7)
        assertEquals(listOf(listOf(0, 1), listOf(5, 6)), checked)
        assertTrue(QueueDuplicates.checkedPositions(emptyList(), 3).isEmpty())
        // Titles translated into other words are another language's version as far as a name can tell.
        assertTrue(QueueDuplicates.checkedPositions(listOf(listOf(0, 1)), 3, QueueDuplicates.TITLES_TRANSLATED).isEmpty())
        assertEquals(listOf(listOf(0, 1)), QueueDuplicates.checkedPositions(listOf(listOf(0, 1)), 3, QueueDuplicates.TITLES_SCRIPT))
    }
}
