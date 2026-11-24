package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueDuplicates
import com.music.bitchord.playback.QueueTidy
import com.music.bitchord.playback.SameSongLinks

/** The queue as the tidy button saw it, and the panel's ways to read it again and to take a song out. */
class QueueTidyAsk(
    val queue: List<Song>,
    val currentIndex: Int,
    val latest: () -> List<Song>,
    val remove: (Int) -> Unit,
)

/** Hosts the queue's tidy window and hands the shared queue panel its button. */
@Composable
internal fun QueueTidyAlerts() {
    DisposableEffect(Unit) {
        QueueTidy.open = { queue, current, latest, remove -> KoAlerts.queueTidy.value = QueueTidyAsk(queue, current, latest, remove) }
        onDispose { QueueTidy.open = null }
    }
    val ask by KoAlerts.queueTidy.collectAsStateWithLifecycle()
    ask?.let { shown -> QueueTidyWindow(shown) { KoAlerts.queueTidy.value = null } }
}

private fun Song.where(): String = listOfNotNull(
    artist.takeIf { it.isNotBlank() },
    durationText?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * The same recording queued more than once, from the one playing on: each
 * group with the copy kept and the others ticked to go. Nothing leaves the
 * queue until the listener says so.
 */
@Composable
private fun QueueTidyWindow(ask: QueueTidyAsk, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    // The one playing first, then what is still to come: positions here are [ask.currentIndex] on.
    val songs = remember(ask) { ask.queue.drop(ask.currentIndex.coerceAtLeast(0)).take(QueueDuplicates.MAX_SONGS) }
    val claude = LyricsTranslation.claudeEnabled && LyricsTranslation.claudeKeyUsable
    var groups by remember(ask) { mutableStateOf<List<QueueDuplicates.Group>?>(null) }
    var failed by remember(ask) { mutableStateOf(false) }
    LaunchedEffect(ask) {
        groups = try {
            QueueDuplicates.find(songs, protectedCount = 1, useClaude = claude)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            emptyList()
        }
    }
    var marked by remember(groups) { mutableStateOf(groups.orEmpty().flatMap { it.remove }.toSet()) }
    var keepTogether by remember { mutableStateOf(true) }
    val keepText = stringResource(R.string.queue_tidy_keep)
    val removeText = stringResource(R.string.queue_tidy_remove)
    val playingText = stringResource(R.string.queue_tidy_playing)
    KoWindow(title = stringResource(R.string.queue_tidy_title), onBack = onDismiss) {
        val found = groups
        if (found == null) {
            windowText(app.getString(if (claude) R.string.queue_tidy_looking else R.string.queue_tidy_looking_rules))
            return@KoWindow
        }
        if (found.isEmpty()) {
            windowText(
                app.getString(
                    when {
                        failed -> R.string.queue_tidy_failed
                        claude -> R.string.queue_tidy_none
                        else -> R.string.queue_tidy_none_rules
                    },
                ),
            )
            return@KoWindow
        }
        windowText(app.getString(R.string.queue_tidy_summary, found.size))
        found.forEachIndexed { index, group ->
            val rows = listOf(group.keep) + group.remove
            windowCard(
                rows = rows,
                header = "${index + 1}. ${songs[group.keep].title}",
                footer = group.reason.takeIf { it.isNotBlank() },
                key = { "tidy-$index-$it" },
            ) { position ->
                val song = songs[position]
                val playing = position == 0
                WindowChoice(
                    label = (if (position == group.keep) (if (playing) playingText else keepText) else if (position in marked) removeText else keepText) +
                        " · " + song.title,
                    detail = song.where(),
                    checked = position in marked,
                    enabled = position != group.keep,
                ) {
                    marked = if (position in marked) marked - position else marked + position
                }
            }
        }
        windowCard(rows = listOf(Unit), footer = app.getString(R.string.queue_tidy_remember_footer)) {
            WindowSwitch(label = app.getString(R.string.queue_tidy_remember), checked = keepTogether) { keepTogether = it }
        }
        windowActions(
            listOf(
                WindowButton(
                    label = app.getString(R.string.queue_tidy_remove_button, marked.size),
                    destructive = true,
                    enabled = marked.isNotEmpty(),
                ) {
                    val removed = removeFromQueue(ask, songs, marked)
                    if (keepTogether) {
                        found.forEach { group ->
                            val gone = group.remove.filter { it in marked }.map { songs[it] }
                            if (gone.isNotEmpty()) SameSongLinks.link(songs[group.keep], gone)
                        }
                    }
                    Toast.makeText(app, app.getString(R.string.queue_tidy_removed, removed), Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ),
        )
    }
}

/**
 * Takes the marked songs out of the queue as it is now: each found again by
 * its entry where the queue has moved since the window opened, the last first
 * so the positions before it hold. A song no longer in the queue is skipped.
 */
private fun removeFromQueue(ask: QueueTidyAsk, songs: List<Song>, marked: Set<Int>): Int {
    val now = ask.latest()
    fun same(a: Song, b: Song) = a.videoId == b.videoId && (a.queueEntryId == null || a.queueEntryId == b.queueEntryId)
    val positions = marked.mapNotNull { position ->
        val song = songs.getOrNull(position) ?: return@mapNotNull null
        val expected = ask.currentIndex + position
        if (now.getOrNull(expected)?.let { same(song, it) } == true) {
            expected
        } else {
            now.indices.firstOrNull { it > ask.currentIndex && same(song, now[it]) }
        }
    }.distinct().sortedDescending()
    positions.forEach(ask.remove)
    return positions.size
}
