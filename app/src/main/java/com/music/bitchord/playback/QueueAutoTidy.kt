package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings' "tidy radio songs automatically": once radio or AutoPlay has added
 * songs, the queue from the one playing on is looked through by
 * [QueueDuplicates], and a song radio or AutoPlay added that repeats a
 * recording already queued is taken out. Songs the listener queued, and the
 * playlist or album they chose, are never touched. Each queued song is asked
 * about once; what Claude found is kept as one song ([SameSongLinks]), so the
 * station leaves it out next time without asking.
 */
internal class QueueAutoTidy(private val scope: CoroutineScope, private val player: () -> Player?) {
    private val asked = LinkedHashSet<String>()
    private var job: Job? = null

    private fun keyOf(item: MediaItem): String = item.mediaId + "#" + (mediaItemEntryIds[item] ?: "")

    /** Added by the station rather than chosen: what this may take out. */
    private fun fromStation(item: MediaItem): Boolean = item.fromAutoplay || item.toSong().radioName != null

    fun onQueueChanged() {
        if (!AppSettings.queueTidyAuto.value) return
        if (!LyricsTranslation.claudeEnabled || !LyricsTranslation.claudeKeyUsable) return
        // A shared queue is the room's, not this phone's to edit.
        if (ListenTogether.state.value.inParty) return
        job?.cancel()
        job = scope.launch {
            // A station lands in more than one change: wait for it to settle.
            delay(SETTLE_MS)
            val exo = player() ?: return@launch
            val start = exo.currentMediaItemIndex.coerceAtLeast(0)
            val end = minOf(exo.mediaItemCount, start + QueueDuplicates.MAX_SONGS)
            if (end - start < 2) return@launch
            val items = (start until end).map { exo.getMediaItemAt(it) }
            val fresh = items.drop(1).filter { fromStation(it) && keyOf(it) !in asked }
            if (fresh.isEmpty()) return@launch
            val songs = items.map { it.toSong() }
            val groups = withContext(Dispatchers.IO) {
                runCatching { QueueDuplicates.find(songs, protectedCount = 1, useClaude = true) }.getOrNull()
            } ?: return@launch
            items.forEach { asked += keyOf(it) }
            while (asked.size > MAX_REMEMBERED) asked.remove(asked.first())
            val now = player() ?: return@launch
            val taken = mutableListOf<Pair<Int, Int>>()
            groups.forEach { group ->
                group.remove.filter { fromStation(items[it]) }.forEach { position -> taken += group.keep to position }
            }
            // Found again where the queue has moved since, and taken out last first.
            val indices = taken.mapNotNull { (keep, position) ->
                val key = keyOf(items[position])
                val expected = start + position
                val at = if (expected < now.mediaItemCount && keyOf(now.getMediaItemAt(expected)) == key) {
                    expected
                } else {
                    (now.currentMediaItemIndex + 1 until now.mediaItemCount).firstOrNull { keyOf(now.getMediaItemAt(it)) == key }
                }
                at?.let { Triple(it, keep, position) }
            }.sortedByDescending { it.first }
            indices.forEach { (index, keep, position) ->
                now.removeMediaItem(index)
                TrackLog.i(
                    "Queue",
                    "tidy: took out \"${songs[position].title}\" (${songs[position].videoId}): the same recording as \"${songs[keep].title}\"",
                    about = songs[keep].videoId,
                )
            }
            // Kept as one song, so the station leaves it out next time for nothing.
            groups.filter { it.byClaude }.forEach { group ->
                val gone = group.remove.filter { position -> indices.any { it.third == position } }.map { songs[it] }
                if (gone.isNotEmpty()) SameSongLinks.link(songs[group.keep], gone)
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 4_000L
        const val MAX_REMEMBERED = 2_000
    }
}
