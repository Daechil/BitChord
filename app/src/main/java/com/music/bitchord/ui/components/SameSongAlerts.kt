package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.SameSongLinks
import com.music.bitchord.ui.icons.SfIcons

/** A song to group with its other uploads, and the queue it was opened beside. */
class SameSongAsk(val song: Song, val queue: List<Song>)

private fun Song.where(): String = listOfNotNull(
    artist.takeIf { it.isNotBlank() },
    durationText?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** The song menu's row: how many other uploads it is grouped with, when any. */
@Composable
internal fun SameSongRow(song: Song, accent: Color, onOpen: () -> Unit) {
    val groups by SameSongLinks.groups.collectAsStateWithLifecycle()
    val grouped = remember(groups, song.videoId) { groups.firstOrNull { g -> g.any { it.id == song.videoId } }?.size }
    ActionRow(
        icon = SfIcons.Groups,
        label = stringResource(R.string.same_song_link),
        value = grouped?.let { stringResource(R.string.same_song_count, it) },
        accent = accent,
        onClick = onOpen,
    )
}

@Composable
internal fun SameSongAlerts() {
    val ask by KoAlerts.sameSong.collectAsStateWithLifecycle()
    ask?.let { shown -> SameSongWindow(shown) { KoAlerts.sameSong.value = null } }
    val list by KoAlerts.sameSongList.collectAsStateWithLifecycle()
    if (list) SameSongListWindow { KoAlerts.sameSongList.value = false }
}

/**
 * The song's other uploads, picked from the queue: the ones it is already
 * grouped with ticked, then the queue, songs of about the same length first —
 * two uploads of one recording are the same length give or take a few seconds.
 */
@Composable
private fun SameSongWindow(ask: SameSongAsk, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val song = ask.song
    val groups by SameSongLinks.groups.collectAsStateWithLifecycle()
    val already = remember(ask) {
        SameSongLinks.groupOf(song.videoId).orEmpty().filter { it.id != song.videoId }.map { it.toSong() }
    }
    val queued = remember(ask) {
        val seen = HashSet<String>().apply { add(song.videoId); already.forEach { add(it.videoId) } }
        val length = song.durationMillis()
        ask.queue.filter { seen.add(it.videoId) }
            .withIndex()
            .sortedWith(
                compareBy(
                    { (_, s) -> if (length > 0 && s.durationMillis() > 0 && kotlin.math.abs(s.durationMillis() - length) <= CLOSE_MS) 0 else 1 },
                    { (i, _) -> i },
                ),
            )
            .map { it.value }
    }
    var ticked by remember(ask) { mutableStateOf(already.map { it.videoId }.toSet()) }
    val closeLabel = stringResource(R.string.same_song_close_length)
    val length = song.durationMillis()
    fun detailOf(s: Song): String {
        val close = length > 0 && s.durationMillis() > 0 && kotlin.math.abs(s.durationMillis() - length) <= CLOSE_MS
        return listOf(s.where(), if (close) closeLabel else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }
    val chosen = (already + queued).filter { it.videoId in ticked }
    val unlinking = chosen.isEmpty() && already.isNotEmpty()
    KoWindow(title = stringResource(R.string.same_song_title), onBack = onDismiss) {
        windowText(app.getString(R.string.same_song_explain, song.title))
        if (already.isNotEmpty()) {
            windowCard(rows = already, header = app.getString(R.string.same_song_linked_header), key = { "linked-" + it.videoId }) { s ->
                WindowChoice(label = s.title, detail = detailOf(s), checked = s.videoId in ticked) {
                    ticked = if (s.videoId in ticked) ticked - s.videoId else ticked + s.videoId
                }
            }
        }
        if (queued.isEmpty()) {
            windowText(app.getString(R.string.same_song_none), header = app.getString(R.string.same_song_queue_header))
        } else {
            windowCard(rows = queued, header = app.getString(R.string.same_song_queue_header), key = { "queue-" + it.videoId }) { s ->
                WindowChoice(label = s.title, detail = detailOf(s), checked = s.videoId in ticked) {
                    ticked = if (s.videoId in ticked) ticked - s.videoId else ticked + s.videoId
                }
            }
        }
        windowActions(
            listOf(
                WindowButton(
                    label = if (unlinking) app.getString(R.string.same_song_unlink) else app.getString(R.string.same_song_save, chosen.size + 1),
                    destructive = unlinking,
                    enabled = chosen.isNotEmpty() || unlinking,
                ) {
                    already.filter { it.videoId !in ticked }.forEach { SameSongLinks.remove(it.videoId) }
                    if (chosen.isNotEmpty()) {
                        SameSongLinks.link(song, chosen)
                        Toast.makeText(app, app.getString(R.string.same_song_saved, chosen.size + 1), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(app, R.string.same_song_unlinked, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                WindowButton(label = app.getString(R.string.same_song_all, groups.size)) {
                    KoAlerts.sameSongList.value = true
                },
            ),
            footer = app.getString(R.string.same_song_footer),
        )
    }
}

/** Every group the listener has made, each with a way to undo it. */
@Composable
private fun SameSongListWindow(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val groups by SameSongLinks.groups.collectAsStateWithLifecycle()
    KoWindow(title = stringResource(R.string.same_song_all_title), onBack = onDismiss) {
        if (groups.isEmpty()) {
            windowText(app.getString(R.string.same_song_all_none))
            return@KoWindow
        }
        groups.forEachIndexed { index, group ->
            val rows: List<Any> = group + listOf(UNLINK)
            windowCard(rows = rows, header = "${index + 1}. ${group.first().title}", key = { row -> if (row is SameSongLinks.Member) "g$index-${row.id}" else "g$index-unlink" }) { row ->
                if (row is SameSongLinks.Member) {
                    WindowLine(title = row.title, detail = listOfNotNull(row.artist.takeIf { it.isNotBlank() }, row.duration).joinToString(" · "))
                } else {
                    WindowAction(label = app.getString(R.string.same_song_unlink), destructive = true) {
                        SameSongLinks.removeGroup(index)
                        Toast.makeText(app, R.string.same_song_unlinked, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

private object UNLINK

/** Lengths this close are worth putting first: one recording uploaded twice. */
private const val CLOSE_MS = 3_000L
