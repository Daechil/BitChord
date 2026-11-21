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
    fun `the japanese and english titles by one artist at one length`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "きみも悪い人でよかった", "PinocchioP", "3:52"),
            song("b", "I'm Glad You're Evil Too (feat. Hatsune Miku)", "PinocchioP", "3:52"),
        ),
    )

    @Test
    fun `a localised artist name is the same channel`() = assertTrue(
        QueueBuilder.isSameRecording(
            song("a", "アイドル", "YOASOBI", "3:33", artistId = "UCvpredjG93ifbCP1Y77JyFA"),
            song("b", "Idol", "요아소비", "3:33", artistId = "UCvpredjG93ifbCP1Y77JyFA"),
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
                song("c", "きみも悪い人でよかった", "PinocchioP", "3:52"),
                song("d", "I'm Glad You're Evil Too (feat. Hatsune Miku)", "PinocchioP", "3:52"),
            ),
            limit = 10,
        )
        assertEquals(listOf("a", "c"), extra.map { it.videoId })
    }
}
