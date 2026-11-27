package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * A station's candidates with the songs heard lately held back.
 *
 * YouTube's radio for a song comes back much the same every time, so a
 * listener who starts radio most days hears yesterday's station again. Given
 * what was heard through recently, the unheard candidates go first, in the
 * station's own order, and the heard ones after them, longest-unheard first —
 * so a station still fills when it has little new, and fills with what has
 * been out of rotation longest.
 *
 * [drop] leaves the heard ones out instead, unless that would leave fewer than
 * [MIN_FRESH]: an empty station is an error on an explicit radio start and an
 * endless retry for AutoPlay, and a few long-unheard songs are the lesser harm.
 */
object FreshRadio {

    /** Fewer unheard songs than this and [drop] keeps the heard ones after them after all. */
    const val MIN_FRESH = 3

    /** A song heard through, and the day it last was (epoch day). */
    class Heard(val song: Song, val lastDay: Long)

    class Result(
        val songs: List<Song>,
        /** Candidates found among the heard songs. */
        val heard: Int,
        /** Whether the heard ones were left out, rather than put last. */
        val dropped: Boolean,
    )

    fun order(candidates: List<Song>, heard: List<Heard>, drop: Boolean): Result {
        if (candidates.isEmpty() || heard.isEmpty()) return Result(candidates, 0, false)
        val find = QueueBuilder.indexAmong(heard.map { it.song })
        val fresh = mutableListOf<Song>()
        val stale = mutableListOf<Pair<Song, Long>>()
        for (song in candidates) {
            val at = find(song)
            if (at < 0) fresh += song else stale += song to heard[at].lastDay
        }
        if (stale.isEmpty()) return Result(candidates, 0, false)
        val leaveOut = drop && fresh.size >= MIN_FRESH
        val songs = if (leaveOut) fresh else fresh + stale.sortedBy { it.second }.map { it.first }
        return Result(songs, stale.size, leaveOut)
    }
}
