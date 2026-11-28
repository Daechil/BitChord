package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** KO: a search row's type word in the phone's language is no artist. */
class LocalizedRowTypeTest {
    private fun row(videoId: String, title: String, vararg subtitle: String, width: Int = 120, height: Int = 120): String {
        val runs = subtitle.toList().joinToString(""", { "text": " • " }, """) { """{ "text": "$it" }""" }
        return """
        { "musicResponsiveListItemRenderer": {
          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
            { "url": "https://i.ytimg.com/vi/$videoId/x.jpg", "width": $width, "height": $height }
          ] } } },
          "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": {
            "playNavigationEndpoint": { "watchEndpoint": { "videoId": "$videoId" } }
          } } } },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$title" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ $runs ] } } }
          ]
        } }
        """
    }

    private fun parse(vararg rows: String): List<com.music.bitchord.data.model.Song> {
        val json: JsonObject = Json.parseToJsonElement("""{ "contents": [ ${rows.joinToString(",")} ] }""").jsonObject
        return InnertubeParser.parseSearchSongs(json)
    }

    @Test
    fun aKoreanTypeWordIsSkippedForTheArtist() {
        val song = parse(row("hope1", "HOPE", "노래", "NF", "3:51")).single()
        assertEquals("NF", song.artist)
    }

    @Test
    fun aRowWithOnlyTheTypeWordDoesNotCreditIt() {
        val song = parse(row("hope2", "HOPE", "노래", "3:51")).single()
        assertNotEquals("노래", song.artist)
    }

    @Test
    fun videoRowsLeaveASongSearchInAnyLanguage() {
        // Square thumbnails: only the type word says these are videos.
        val songs = parse(
            row("s1", "HOPE", "노래", "NF", "3:51"),
            row("mv1", "HOPE", "동영상", "NF", "4:02"),
            row("mv2", "夜に駆ける", "動画", "YOASOBI", "4:21"),
            row("mv3", "Creep", "Video", "Radiohead", "3:58"),
        )
        assertEquals(listOf("s1"), songs.map { it.videoId })
    }

    @Test
    fun englishRowsReadAsBefore() {
        val song = parse(row("en1", "Creep", "Song", "Radiohead", "3:58")).single()
        assertEquals("Radiohead", song.artist)
    }

    @Test
    fun theAppsOtherLanguagesAreReadToo() {
        val songs = parse(
            row("es1", "HOPE", "Canción", "NF", "3:51"),
            row("de1", "HOPE", "Titel", "NF", "3:51"),
            row("ru1", "HOPE", "Песня", "NF", "3:51"),
            row("zh1", "HOPE", "歌曲", "NF", "3:51"),
            row("tr1", "HOPE", "Şarkı", "NF", "3:51"),
            row("es2", "HOPE", "Vídeo", "NF", "4:02"),
        )
        assertEquals(listOf("es1", "de1", "ru1", "zh1", "tr1"), songs.map { it.videoId })
        assertTrue(songs.all { it.artist == "NF" })
    }

    @Test
    fun aDateOrAPlayCountIsNoArtist() {
        val songs = parse(
            row("d1", "Spring day", "노래", "2017. 12. 29.", "4:40"),
            row("d2", "Spring day", "노래", "2017년 12월 29일", "4:40"),
            row("d3", "Spring day", "Song", "Dec 29, 2017", "4:40"),
            row("t1", "Spring day", "노래", "조회수 120만회", "4:40"),
            row("t2", "Spring day", "曲", "1.2億回再生", "4:40"),
        )
        assertEquals(5, songs.size)
        songs.forEach { song ->
            assertTrue(song.artist.none { it.isDigit() }, "read as artist: ${song.artist}")
        }
    }

    @Test
    fun anArtistWithDigitsStaysAnArtist() {
        val songs = parse(row("a1", "Cheer Up", "노래", "TWICE", "3:28"), row("a2", "봄이 좋냐??", "노래", "10cm", "3:33"))
        assertEquals(listOf("TWICE", "10cm"), songs.map { it.artist })
    }
}
