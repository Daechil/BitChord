package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.SongMatch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One song filed under two video ids — a single and its album cut, a
 * repackage — which YouTube Music happily holds twice in one playlist, and a
 * check on the id alone never sees.
 */
fun Song.sameEntryAs(other: Song): Boolean =
    videoId != other.videoId &&
        SongMatch.sameEntry(title, artist, durationMillis(), other.title, other.artist, other.durationMillis())

/** The first of [among] that is [song] under another id, or null. */
fun similarIn(song: Song, among: List<Song>?): Song? = among?.firstOrNull { song.sameEntryAs(it) }

/** Adding [song] where [existing] — the same song, another release — already is. */
class SimilarAsk(
    val song: Song,
    val existing: Song,
    /** The playlists it was held back from, by name. */
    val playlists: List<String>,
    val addAnyway: () -> Unit,
)

/** A playlist's songs, to look through for one song held twice. */
class DuplicateAsk(
    val title: String,
    val songs: List<Song>,
    /** Takes these entries out of the playlist; true when it worked. */
    val remove: suspend (List<Song>) -> Boolean,
)

private fun Song.where(): String = listOfNotNull(
    artist.takeIf { it.isNotBlank() },
    albumName?.takeIf { it.isNotBlank() },
    durationText?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** Asks whether to add a song another release of which is already in the playlist. */
@Composable
internal fun SimilarSongAlert(hazeState: HazeState) {
    val ask by KoAlerts.similarSong.collectAsStateWithLifecycle()
    val shown = ask ?: return
    BackHandler { KoAlerts.similarSong.value = null }
    ConfirmationAlert(
        hazeState = hazeState,
        title = stringResource(R.string.similar_song_title),
        description = stringResource(
            R.string.similar_song_description,
            shown.playlists.joinToString(", "),
            shown.existing.title,
            shown.existing.where(),
            shown.song.title,
            shown.song.where(),
        ),
        confirmLabel = stringResource(R.string.similar_song_add_anyway),
        onConfirm = {
            KoAlerts.similarSong.value = null
            shown.addAnyway()
        },
        onDismiss = { KoAlerts.similarSong.value = null },
    )
}

/** The playlist's duplicates window, while [KoAlerts.duplicates] holds one. */
@Composable
internal fun DuplicatesAlerts() {
    val ask by KoAlerts.duplicates.collectAsStateWithLifecycle()
    val shown = ask ?: return
    DuplicatesWindow(shown) { KoAlerts.duplicates.value = null }
}

/**
 * The songs a playlist holds more than once under different ids, a group
 * each: the first copy kept, the later ones ticked to go — the listener
 * changes the ticks, and nothing goes without a confirmation.
 */
@Composable
private fun DuplicatesWindow(ask: DuplicateAsk, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val groups = remember(ask) {
        val used = BooleanArray(ask.songs.size)
        buildList {
            ask.songs.forEachIndexed { i, song ->
                if (used[i]) return@forEachIndexed
                val group = mutableListOf(song)
                for (j in i + 1 until ask.songs.size) {
                    if (!used[j] && song.sameEntryAs(ask.songs[j])) {
                        used[j] = true
                        group += ask.songs[j]
                    }
                }
                if (group.size > 1) add(group.toList())
            }
        }
    }
    fun keyOf(song: Song) = song.setVideoId ?: song.videoId
    var marked by remember(groups) { mutableStateOf(groups.flatMap { it.drop(1) }.filter { it.setVideoId != null }.map(::keyOf).toSet()) }
    var confirming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }
    val removable = groups.flatten().filter { keyOf(it) in marked && it.setVideoId != null }
    val doneText = stringResource(R.string.duplicates_removed, removable.size)
    val failedText = stringResource(R.string.ko_delete_failed)
    val positionOf = remember(ask) { ask.songs.withIndex().associate { (i, s) -> (s.setVideoId ?: s.videoId) to i + 1 } }
    val positionText = stringResource(R.string.duplicates_position)
    val keepText = stringResource(R.string.duplicates_keep)
    val removeText = stringResource(R.string.duplicates_remove_mark)
    KoWindow(
        title = stringResource(R.string.duplicates_title),
        onBack = { if (confirming) confirming = false else onDismiss() },
        overlay = {
            if (confirming) {
                ConfirmationAlert(
                    hazeState = hazeState,
                    title = stringResource(R.string.duplicates_confirm_title, removable.size),
                    description = stringResource(R.string.duplicates_confirm_description, ask.title),
                    confirmLabel = stringResource(R.string.ko_delete),
                    onConfirm = {
                        confirming = false
                        removing = true
                        scope.launch {
                            val ok = try {
                                ask.remove(removable)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            removing = false
                            Toast.makeText(app, if (ok) doneText else failedText, Toast.LENGTH_LONG).show()
                            if (ok) onDismiss()
                        }
                    },
                    onDismiss = { confirming = false },
                    frosted = false,
                )
            }
        },
    ) {
        if (groups.isEmpty()) {
            windowText(app.getString(R.string.duplicates_none, ask.title))
            return@KoWindow
        }
        windowText(app.getString(R.string.duplicates_summary, groups.size, ask.songs.size))
        groups.forEachIndexed { index, group ->
            windowCard(rows = group, header = "${index + 1}. ${group.first().title}", key = { "dup-$index-" + keyOf(it) }) { song ->
                val key = keyOf(song)
                val removableRow = song.setVideoId != null
                WindowChoice(
                    label = (if (key in marked) removeText else keepText) + " · " + positionText.format(positionOf[key] ?: 0),
                    detail = song.where(),
                    checked = key in marked,
                    enabled = removableRow && !removing,
                ) {
                    marked = if (key in marked) marked - key else marked + key
                }
            }
        }
        windowActions(
            listOf(
                WindowButton(
                    label = app.getString(R.string.duplicates_remove_button, removable.size),
                    destructive = true,
                    enabled = removable.isNotEmpty() && !removing,
                ) { confirming = true },
            ),
            footer = app.getString(R.string.duplicates_footer),
        )
    }
}
