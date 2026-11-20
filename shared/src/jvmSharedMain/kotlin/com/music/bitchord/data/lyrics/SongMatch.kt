package com.music.bitchord.data.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Whether a catalogue's answer is the song that is playing, by its name.
 *
 * Used where an answer would otherwise be taken on trust: [BiniLyrics]' search
 * returns its best guess, and the ISRC of that guess is handed to every source
 * that asks by recording — lrc.red among them, which then serves that
 * recording's words without a second look. A guess for the wrong song by the
 * same artist ("ピースサイン" answered with another Kenshi Yonezu song) became
 * the wrong lyrics scrolling in time with the right one.
 *
 * Looser than [LrcRed.best], which picks among many hits: one name may be in
 * Japanese and the other in English ("ピースサイン - Peace Sign" against
 * "ピースサイン"), so the title passes when any part of one is a part of the
 * other. What it will not pass is a different title.
 */
object SongMatch {

    /** Off only in the live check, to see what the check takes away. */
    @Volatile
    internal var bypass = false

    /** Lengths further apart than this are different recordings, whatever they are called. */
    private const val FAR_SECONDS = 10.0

    /** Close enough that the length alone stands in for an artist written another way. */
    private const val NEAR_SECONDS = 3.0

    /**
     * True when [foundTitle] is [title], and [foundArtist] is [artist] or, written
     * in another script, the length agrees to within [NEAR_SECONDS].
     */
    fun sameSong(
        title: String,
        artist: String,
        durationMs: Long,
        foundTitle: String?,
        foundArtist: String?,
        foundSeconds: Double?,
    ): Boolean {
        if (bypass) return true
        if (foundTitle.isNullOrBlank()) return false
        if (!sameTitle(title, foundTitle)) return false
        // "Dynamite" is not "Dynamite (Slow Jam Remix)": the same words, sung to
        // other timings. Labels like "Remastered" are not a different recording.
        if (versionOf(title) != versionOf(foundTitle)) return false
        val gap = if (durationMs > 0 && foundSeconds != null && foundSeconds > 0) {
            abs(durationMs / 1000.0 - foundSeconds)
        } else {
            null
        }
        if (gap != null && gap > FAR_SECONDS) return false
        val wanted = artistsOf(artist)
        val found = artistsOf(foundArtist.orEmpty())
        if (wanted.isEmpty() || found.isEmpty()) return true
        if (wanted.any { w -> found.any { f -> w == f || w.contains(f) || f.contains(w) } }) return true
        // An artist written in another script (Kenshi Yonezu / 米津玄師) can't be
        // compared by its letters, so the length stands in for it. Two names in
        // the same script that differ are two artists: a cover.
        if (sameScript(artist, foundArtist.orEmpty())) return false
        return gap != null && gap <= NEAR_SECONDS
    }

    /** Any part of one title — outside or inside its brackets, either side of " - " or "/" — is a part of the other. */
    fun sameTitle(a: String, b: String): Boolean {
        if (bypass) return true
        val left = partsOf(a)
        val right = partsOf(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return left.any { l -> right.any { r -> l == r || (minOf(l.length, r.length) >= 4 && (l.contains(r) || r.contains(l))) } }
    }

    private fun partsOf(title: String): Set<String> {
        val bracketed = BRACKETED.findAll(title).map { it.value.drop(1).dropLast(1) }.toList()
        val outside = title.replace(BRACKETED, " ")
        return (outside.split(SPLITTERS) + bracketed + listOf(outside))
            .map(::normalized)
            .filter { it.isNotEmpty() && it !in NOT_A_TITLE }
            .toSet()
    }

    /** The same credited artists on both sides, no more and no fewer. */
    fun sameArtists(a: String, b: String): Boolean = artistsOf(a) == artistsOf(b)

    /** The version words of a title, from its brackets and after a " - ". */
    fun versionOf(title: String): Set<String> {
        val extras = BRACKETED.findAll(title).joinToString(" ") { it.value } + " " + title.substringAfter(" - ", "")
        return Normalizer.normalize(extras, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    /** Whether both names are written mostly in Latin letters, or both mostly not. */
    private fun sameScript(a: String, b: String): Boolean {
        fun latin(value: String): Boolean? {
            val letters = value.filter { it.isLetter() }
            if (letters.isEmpty()) return null
            return letters.count { it.code < 0x250 } * 2 >= letters.length
        }
        val left = latin(a) ?: return false
        val right = latin(b) ?: return false
        return left == right
    }

    /** Words in brackets that make it a different recording; see [LrcRed]. */
    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
        "karaoke", "cappella", "acapella", "demo", "cover", "sped", "slowed", "nightcore",
        "lofi", "orchestral", "extended", "jam", "inst",
    )

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    /** Lower case, accents off, only letters and digits, no spaces: "Kenshi Yonezu" is "kenshiyonezu". */
    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    private val BRACKETED = Regex("""[(\[（【「『][^)\]）】」』]*[)\]）】」』]""")
    private val SPLITTERS = Regex("""\s+[-–—/|]\s+|\s*/\s*""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|&|;|/|、|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Bracket words that say how it was released, not what it is called. */
    private val NOT_A_TITLE = setOf(
        "remastered", "remaster", "live", "remix", "acoustic", "version", "edit", "radioedit",
        "single", "albumversion", "explicit", "clean", "mono", "stereo", "instrumental", "tvsize",
    )
}
