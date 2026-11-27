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
        // Worked out once per song rather than once per comparison: [existing]
        // is the whole queue, hundreds of songs, and this runs on the main thread.
        val taken = existing.mapTo(mutableListOf(), ::Key)
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
        val keys = ordered.map(::Key)
        // Of two uploads of one song among the candidates, the one in its own
        // language and under its maker's name stays, wherever the mix put it.
        val passedOver = keys.indices.filterTo(HashSet()) { i ->
            keys.indices.any { j -> j != i && preferred(keys[j], keys[i]) && same(keys[i], keys[j]) }
        }

        for ((index, candidate) in ordered.withIndex()) {
            if (out.size >= limit) break
            if (index in passedOver) continue
            val key = keys[index]
            val held = taken.firstOrNull { same(it, key) }
            if (held != null) {
                if (!alike(held, key)) linkedSink?.invoke(candidate, held.name, held.id)
                continue
            }

            val artists = artistSet(candidate.artist)
            val capKey = artists.minOrNull()
            if (capKey != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[capKey] ?: 0
                if (played >= cap) continue
                perArtist[capKey] = played + 1
            }

            taken += key
            out += candidate
        }
        return out
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean = same(Key(a), Key(b))

    /**
     * Which of [songs] each song asked about is the same recording as — its
     * index there, or -1 — with [songs]' keys worked out once rather than once
     * per question. For holding a station's candidates against a long list.
     */
    fun indexAmong(songs: List<Song>): (Song) -> Int {
        val keys = songs.map(::Key)
        val byId = HashMap<String, Int>()
        songs.forEachIndexed { index, song -> if (song.videoId.isNotBlank()) byId.putIfAbsent(song.videoId, index) }
        return { song ->
            byId[song.videoId] ?: Key(song).let { key -> keys.indexOfFirst { same(it, key) } }
        }
    }

    /** The listener's own groups of one song, as [SameSongLinks] keeps them; read for every comparison. */
    @Volatile
    private var linkKeys: List<List<Key>> = emptyList()

    /** Told when a candidate is left out only because the listener grouped it with a song already there. */
    @Volatile
    var linkedSink: ((left: Song, kept: String, keptId: String) -> Unit)? = null

    internal fun setLinks(groups: List<List<Song>>) {
        linkKeys = groups.map { group -> group.map { Key(it, grouped = false) } }
    }

    /** The listener's group this song is in — one of its uploads, or another upload of one — or -1. */
    private fun groupOf(key: Key): Int {
        val groups = linkKeys
        if (groups.isEmpty()) return -1
        return groups.indexOfFirst { group -> group.any { alike(it, key) } }
    }

    /** What a comparison reads of a song, worked out once. */
    private class Key(song: Song, grouped: Boolean = true) {
        val id = song.videoId
        val title = normalisedTitle(song.title)
        val pieces = titlePieces(song.title)
        val version = SongMatch.versionOf(song.title)
        val latin = latinOnly(title)
        val artists = artistSet(song.artist)
        /** The credit that names who made it: the first one that is not a voice or a label. */
        val lead = artists.firstOrNull { it !in NOT_THE_MAKER }
        /** The lead's channel, where the lead is the maker rather than a voice. */
        val leadId = song.artistId?.takeIf { lead != null && artists.firstOrNull() == lead }
        /**
         * Billed to the voice it is sung by — Kasane Teto's channel carrying
         * Hiiragi Magnetite's "Tetoris" — so the credit says nothing of who made it.
         */
        val voiceBilled = artists.isNotEmpty() && artists.all { it in NOT_THE_MAKER }
        val ms = song.durationMillis()
        val name = song.title
        /** Worked out last, from the fields above; -1 for none, and for a group's own members. */
        val group: Int = if (grouped) groupOf(this) else -1
    }

    private fun same(a: Key, b: Key): Boolean = alike(a, b) || linked(a, b)

    private fun linked(a: Key, b: Key): Boolean = a.group >= 0 && a.group == b.group

    private fun alike(a: Key, b: Key): Boolean {
        if (a.id == b.id) return true
        if (a.title.isNotEmpty() && a.title == b.title) {
            // One shared name is enough. The two cuts of a track get billed in
            // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
            // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
            if (a.artists.isEmpty() || b.artists.isEmpty() || a.artists.any { it in b.artists }) return true
            // The same title under another credit is one song only where a
            // credit cannot be trusted — one upload billed to the voice that
            // sings it — and the two are the same length to the second. A
            // cover over the original's backing track is just as long, so the
            // length alone is not enough.
            return (a.voiceBilled || b.voiceBilled) && closeLength(a, b, SAME_LENGTH_SECONDS)
        }
        // Past this point nothing in the two names agrees outright, so a
        // remix, a live cut or an acoustic take must say the same of both.
        if (a.version != b.version) return false
        val sameMaker = sameLead(a, b)
        // "テトリス - Tetoris" against "テトリス": one title holds the other's
        // every name. "Part 1" and "Part 2" share a name but neither holds the other.
        if (a.pieces.isNotEmpty() && b.pieces.isNotEmpty() && a.pieces != b.pieces &&
            (b.pieces.containsAll(a.pieces) || a.pieces.containsAll(b.pieces))
        ) {
            return sameMaker || ((a.voiceBilled || b.voiceBilled) && closeLength(a, b, SAME_LENGTH_SECONDS))
        }
        // Not joined: the same maker, a title in another script, the same
        // length. Measured on twenty real stations it joined eight pairs and
        // every one was two songs — Kanaria's "KING" and "エンヴィーベイビー",
        // Stray Kids' "MANIAC" and "락 (樂)", both 183 s — and no true pair.
        return false
    }

    /** One maker, by name or by channel; a voice or a label shared by thousands of songs is not one. */
    private fun sameLead(a: Key, b: Key): Boolean =
        (a.lead != null && a.lead == b.lead) || (a.leadId != null && a.leadId == b.leadId)

    /** Which of two uploads of one song a station keeps: the maker's over the voice's, its own language over a translation. */
    private fun preferred(keep: Key, over: Key): Boolean = when {
        keep.voiceBilled != over.voiceBilled -> !keep.voiceBilled
        keep.latin != over.latin -> !keep.latin
        else -> false
    }

    /** Lengths within [seconds] of each other; false where either is unknown. */
    private fun closeLength(a: Key, b: Key, seconds: Double): Boolean =
        a.ms > 0 && b.ms > 0 && kotlin.math.abs(a.ms - b.ms) <= seconds * 1000

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

    /** YouTube gives lengths in whole seconds: one second apart is the same length rounded two ways. */
    private const val SAME_LENGTH_SECONDS = 1.0

    /**
     * Credits that name the voice a song is sung by, or the label it came out
     * on, rather than who made it: shared by thousands of songs, they say
     * nothing about whether two are one.
     */
    private val NOT_THE_MAKER = setOf(
        "hatsune miku", "初音ミク", "하츠네 미쿠", "miku",
        "kasane teto", "重音テト", "카사네 테토", "teto",
        "kagamine rin", "鏡音リン", "kagamine len", "鏡音レン", "kagamine rin len",
        "megurine luka", "巡音ルカ", "kaito", "meiko", "gumi", "ia", "flower", "v flower",
        "kafu", "可不", "kaai yuki", "yuzuki yukari", "結月ゆかり",
        "otomachi una", "音街ウナ", "zundamon", "ずんだもん", "kotonoha akane", "kotonoha aoi",
        "vocaloid", "utau", "synthesizer v",
        "various artists", "verschiedene interpreten", "disney", "walt disney records",
    )

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
        // " 및 " and "、" are how YouTube Music bills two artists in Korean and Japanese.
        .split(",", "&", "·", "•", ";", "、", " 및 ", " feat", " ft.", " ft ", " x ", " with ")
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
