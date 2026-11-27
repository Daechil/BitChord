package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * KO: radio and AutoPlay with the songs heard lately held back — see
 * [FreshRadio] for the ordering and Settings › KO features › Up next for the
 * period. Off unless a period is chosen.
 *
 * What was heard comes from the listening record the Replay is drawn from,
 * read again at most every [REFRESH_MS]: a station is asked for every ten
 * songs or so, and the record changes by one song at a time.
 */
internal object RecentRadio {

    private const val REFRESH_MS = 5 * 60_000L

    private val lock = Mutex()
    private var cachedDays = 0
    private var cachedAt = 0L
    private var cachedOn: LocalDate? = null
    private var cached: List<FreshRadio.Heard> = emptyList()

    /** [candidates], heard songs moved last or left out; as they came when the setting is off or the record unreadable. */
    suspend fun reorder(candidates: List<Song>): List<Song> {
        val days = AppSettings.radioFreshDays.value
        if (days <= 0 || candidates.isEmpty()) return candidates
        val heard = try {
            heard(days)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            TrackLog.w("BitChord", "recent radio: listening record unreadable (${e.javaClass.simpleName})")
            return candidates
        }
        val result = FreshRadio.order(candidates, heard, AppSettings.radioFreshDrop.value)
        if (result.heard > 0) {
            val how = if (result.dropped) "left out" else "moved back"
            TrackLog.i("BitChord", "recent radio: ${result.heard} of ${candidates.size} heard in ${days}d, $how")
        }
        return result.songs
    }

    private suspend fun heard(days: Int): List<FreshRadio.Heard> = lock.withLock {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        if (days == cachedDays && today == cachedOn && now - cachedAt < REFRESH_MS) return@withLock cached
        val since = today.minusDays(days.toLong())
        val fresh = ListeningStats.heardSince(since).map { track ->
            FreshRadio.Heard(
                Song(videoId = track.id, title = track.title, artist = track.artist, thumbnailUrl = null),
                LocalDate.parse(track.day).toEpochDay(),
            )
        }
        cached = fresh
        cachedDays = days
        cachedOn = today
        cachedAt = now
        fresh
    }
}
