package com.music.bitchord

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBuilderSameSongTest {
    private fun song(id: String, title: String, artist: String, length: String?, artistId: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, artistId = artistId)

    @Test
    fun `the producer's upload and the voice's are one song`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
            song("b", "テトリス - Tetoris (feat. Kasane Teto)", "Kasane Teto", "2:47"),
        ),
    )

    @Test
    fun `a feat credit in the title is not part of the name`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "いますぐ輪廻 - Retry Now", "NAKISO", "3:05"),
            song("b", "いますぐ輪廻 - Retry Now (feat. Hatsune Miku)", "Nakiso", "3:05"),
        ),
    )

    @Test
    fun `two songs of one artist in two scripts but different lengths stay apart`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "アイドル", "YOASOBI", "3:33"),
            song("b", "Biri-Biri", "YOASOBI", "3:01"),
        ),
    )

    @Test
    fun `the same title by someone else at another length is a cover`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "Perfect", "Ed Sheeran", "4:23"),
            song("b", "Perfect", "One Direction", "3:50"),
        ),
    )

    @Test
    fun `a remix after a dash is still a remix`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "Tetoris", "Hiiragi Magnetite", "2:47"),
            song("b", "Tetoris - Remix", "Hiiragi Magnetite", "2:47"),
        ),
    )

    @Test
    fun `the station keeps one of each`() {
        val seed = song("s", "Machine Love", "Jamie Paige", "3:00")
        val extra = QueueBuilder.extend(
            existing = listOf(seed),
            candidates = listOf(
                song("a", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
                song("b", "テトリス - Tetoris (feat. Kasane Teto)", "Kasane Teto", "2:47"),
            ),
            limit = 10,
        )
        assertEquals(listOf("a"), extra.map { it.videoId })
    }

    @Test
    fun `two producers' miku songs are not one song for sharing the voice`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "砂の惑星", "Kenshi Yonezu 및 Hatsune Miku", "3:56"),
            song("b", "Machine Love (feat. Hatsune Miku)", "Jamie Paige, Hatsune Miku", "3:56"),
        ),
    )

    @Test
    fun `a cover over the original's backing track is not the original`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "テトリス", "Hiiragi Magnetite", "2:47"),
            song("b", "テトリス", "Some Utaite", "2:47"),
        ),
    )

    @Test
    fun `part one and part two are two tracks`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "Overture - Act 1", "Composer", "3:00"),
            song("b", "Overture - Act 2", "Composer", "3:00"),
        ),
    )

    @Test
    fun `one title holding the other's name is one song by one maker`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "テトリス", "Hiiragi Magnetite", "2:47"),
            song("b", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
        ),
    )

    @Test
    fun `a label shared by both is not a maker`() = assertFalse(
        QueueBuilder.isSameRecording(
            song("a", "どこまでも", "Disney", "2:42"),
            song("b", "Let It Go", "Disney", "2:42"),
        ),
    )

    @Test
    fun `the maker's upload is the one kept over the voice's`() {
        val seed = song("s", "Machine Love", "Jamie Paige", "3:00")
        val extra = QueueBuilder.extend(
            existing = listOf(seed),
            candidates = listOf(
                song("voice", "テトリス - Tetoris (feat. Kasane Teto)", "Kasane Teto", "2:47"),
                song("maker", "テトリス - Tetoris", "Hiiragi Magnetite", "2:47"),
            ),
            limit = 10,
        )
        assertEquals(listOf("maker"), extra.map { it.videoId })
    }

    // Pairs the other-script rule joined on real stations: every one two songs.
    @Test
    fun `one maker's two songs of one length in two scripts stay apart`() {
        assertFalse(QueueBuilder.isSameRecording(song("a", "KING", "Kanaria", "2:16"), song("b", "エンヴィーベイビー", "Kanaria", "2:16")))
        assertFalse(QueueBuilder.isSameRecording(song("a", "MANIAC", "Stray Kids", "3:03"), song("b", "락 (樂) LALALALA", "Stray Kids", "3:03")))
        assertFalse(QueueBuilder.isSameRecording(song("a", "春を告げる - Haru wo Tsugeru", "yama", "3:17"), song("b", "Film", "yama", "3:16")))
        assertFalse(QueueBuilder.isSameRecording(song("a", "アドレナ", "YOASOBI", "3:06"), song("b", "KILLA", "YOASOBI", "3:05")))
    }

    @Test
    fun `a two-name title and its first name are one song`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "春を告げる - Haru wo Tsugeru", "yama", "3:17"),
            song("b", "春を告げる", "yama", "3:17"),
        ),
    )
}
