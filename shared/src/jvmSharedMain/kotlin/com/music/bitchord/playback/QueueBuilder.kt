package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.SongMatch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import java.util.Locale

/**
 * Builds the station that plays on after a one-off song.
 *
 * Shared: Android and the desktop assemble the same station from the same
 * watch queue, so the de-duplication and the per-artist caps are one
 * implementation rather than two that drift.
 *
 * Two problems this exists to solve:
 *
 *  - YouTube's watch queues routinely carry the same recording twice — the
 *    official audio and the music video are separate videoIds with near
 *    identical titles — so de-duping on id alone lets a track play, then play
 *    again as its video. Songs are matched on a normalised title plus lead
 *    artist instead.
 *
 *  - A radio mix leans hard on the seed's own artist. Capping how many tracks
 *    any one artist contributes keeps a station from turning into a single
 *    artist's discography.
 */
object QueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /** Fewer videos than this in a mix and a video station plays on with its songs too. */
    const val MIN_VIDEO_STATION = 3

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     */
    fun extend(existing: List<Song>, candidates: List<Song>, limit: Int, preferVideos: Boolean = false): List<Song> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<Song>()

        // A mix pairs all but every track with its own music-video upload —
        // the same recording under a different id, titled far enough apart
        // ("Dildaara (Stand By Me)" against "Lyrical Video: Dildara Song")
        // that no title match will catch it. The catalogue cut is the one a
        // music app wants; videos only stand in when there is nothing else.
        // [preferVideos] turns that round for a station playing on from a music
        // video: its videos, where the mix has enough of them to be one.
        val songsOnly = candidates.filterNot { it.isVideo }
        val videosOnly = candidates.filter { it.isVideo }
        val ordered = when {
            preferVideos && videosOnly.size >= MIN_VIDEO_STATION -> videosOnly
            preferVideos -> candidates
            else -> songsOnly.ifEmpty { candidates }
        }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) }) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val titleA = normalisedTitle(a.title)
        val titleB = normalisedTitle(b.title)
        if (titleA.isNotEmpty() && titleA == titleB) {
            // One shared name is enough. The two cuts of a track get billed in
            // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
            // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
            if (sameArtist(a, b)) return true
            // The same title under another name and the same length: the
            // producer's upload and the voice's — "テトリス - Tetoris" by Hiiragi
            // Magnetite and by Kasane Teto. A cover with the same title is
            // rarely the same length to the second.
            return closeLength(a, b, SAME_TITLE_GAP_SECONDS)
        }
        // Past this point nothing in the two names agrees outright, so a
        // remix, a live cut or an acoustic take must say the same of both.
        if (SongMatch.versionOf(a.title) != SongMatch.versionOf(b.title)) return false
        // The song's name written another way by the same artist — its
        // Japanese and English titles ("きみも悪い人でよかった" and "I'm Glad
        // You're Evil Too"), or a localised artist name on one of them
        // ("YOASOBI" and "요아소비") — at the same length.
        if (titleA.isNotEmpty() && titleB.isNotEmpty() && otherScript(titleA, titleB) &&
            sameArtist(a, b) && closeLength(a, b, OTHER_SCRIPT_GAP_SECONDS)
        ) {
            return true
        }
        // "テトリス - Tetoris" against "テトリス": one name of the two in the title.
        val piecesA = titlePieces(a.title)
        val piecesB = titlePieces(b.title)
        if ((piecesA.size > 1 || piecesB.size > 1) && piecesA.any { it in piecesB }) {
            return sameArtist(a, b) || closeLength(a, b, SAME_TITLE_GAP_SECONDS)
        }
        return false
    }

    /** Lengths within [seconds] of each other; false where either is unknown. */
    private fun closeLength(a: Song, b: Song, seconds: Double): Boolean {
        val left = a.durationMillis()
        val right = b.durationMillis()
        return left > 0 && right > 0 && kotlin.math.abs(left - right) <= seconds * 1000
    }

    /** One credited artist in common, by name or by channel. Either side without a credit counts. */
    private fun sameArtist(a: Song, b: Song): Boolean {
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        if (left.isEmpty() || right.isEmpty() || left.any { it in right }) return true
        val idsA = (a.artists.mapNotNull { it.browseId } + listOfNotNull(a.artistId)).toSet()
        val idsB = (b.artists.mapNotNull { it.browseId } + listOfNotNull(b.artistId)).toSet()
        return idsA.any { it in idsB }
    }

    /** One title in Latin letters and the other not: a translation or a reading, not a coincidence of spelling. */
    private fun otherScript(a: String, b: String): Boolean = latinOnly(a) != latinOnly(b)

    private fun latinOnly(value: String): Boolean {
        val letters = value.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.all { it.code < 0x250 }
    }

    /** "テトリス - Tetoris" → its two names, each normalised; a title with one name gives one. */
    private fun titlePieces(raw: String): Set<String> =
        raw.substringBefore(" | ").replace(NOISE, " ")
            .split(" - ", " / ", " – ")
            .map { normalisedTitle(it) }
            .filter { it.length >= 2 }
            .toSet()

    /** Same title, another credit: how close in length makes it one recording. */
    private const val SAME_TITLE_GAP_SECONDS = 2.0

    /** Same artist, the title in another script: closer still, as nothing in the names agrees. */
    private const val OTHER_SCRIPT_GAP_SECONDS = 1.5

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one
     * recording as far as a queue is concerned. Remix and cover markers are
     * deliberately left in — those really are different tracks.
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** The cast behind a credit, split out so billing order stops mattering. */
    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            // A credit is not part of the name: "Tetoris (feat. Kasane Teto)" is "Tetoris".
            """|[(\[]\s*(?:feat|ft|featuring|with)\b[^)\]]*[)\]]""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
