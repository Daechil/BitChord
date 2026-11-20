package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.ClaudeRecommend
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.download.RecommendHistory
import com.music.bitchord.ui.player.claudeFailureLabel
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shows [RecommendWindow] while [KoAlerts.recommend] holds a song. */
@Composable
internal fun RecommendAlerts() {
    val song by KoAlerts.recommend.collectAsStateWithLifecycle()
    val shown = song ?: return
    RecommendWindow(shown) { KoAlerts.recommend.value = null }
}

private enum class RecommendPage { SETUP, RESULTS, HISTORY, ENTRY }

/** What a delete asks before it goes ahead. */
private class RecommendAsk(val title: String, val description: String, val run: suspend () -> Unit)

/** Sonnet's rough cost for one request: the input grows with what is sent, the output with the songs asked for. */
private fun estimateUsd(seeds: Int, avoid: Int, count: Int): Double {
    val input = 900 + 25L * (seeds + avoid)
    val output = 150 + 70L * count
    val (inPrice, outPrice) = AppSettings.claudePricePerMillion(ClaudeRecommend.MODEL)
    return input * inPrice / 1_000_000.0 + output * outPrice / 1_000_000.0
}

@Composable
private fun RecommendWindow(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val format = koFormat()
    var page by remember { mutableStateOf(RecommendPage.SETUP) }
    var basis by remember { mutableStateOf(ClaudeRecommend.BASIS_SONG) }
    var mood by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(20) }
    var novelty by remember { mutableStateOf(ClaudeRecommend.NOVELTY_MIXED) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shownEntry by remember { mutableStateOf<RecommendHistory.Entry?>(null) }
    var fresh by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(0) }
    var checked by remember { mutableStateOf(emptySet<String>()) }
    // Everything suggested since the window opened: left out of "again".
    var suggested by remember { mutableStateOf(emptyList<ClaudeRecommend.Named>()) }
    var asking by remember { mutableStateOf<RecommendAsk?>(null) }
    val history by RecommendHistory.entries.collectAsStateWithLifecycle()
    val queue = remember { KoQueue.upcoming?.invoke().orEmpty() }
    var played by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(Unit) {
        runCatching { RecommendHistory.load() }
        played = try {
            val month = ListeningStats.summary(ReplayPeriod.THIS_MONTH).songs
            (if (month.size >= 5) month else ListeningStats.summary(ReplayPeriod.ALL_TIME).songs).take(HISTORY_SEEDS).map { it.song }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    val failedText = stringResource(R.string.recommend_none_found)
    val failedPrefix = stringResource(R.string.recommend_failed)
    val failureLabels = ClaudeFailure.entries.associateWith { claudeFailureLabel(it) }
    val fromQueue = stringResource(R.string.recommend_from_queue)
    val fromHistory = stringResource(R.string.recommend_from_history)
    val playlistName = stringResource(R.string.recommend_playlist_name)
    val notReady = stringResource(R.string.recommend_no_queue_action)

    fun named(songs: List<Song>) = songs.map { ClaudeRecommend.Named(it.title, it.artist) }
    val seeds: List<Song> = when (basis) {
        ClaudeRecommend.BASIS_QUEUE -> queue.take(QUEUE_SEEDS)
        ClaudeRecommend.BASIS_HISTORY -> played.orEmpty()
        else -> listOf(song)
    }
    val avoidSongs = (queue.take(AVOID_FROM_QUEUE) + (if (basis == ClaudeRecommend.BASIS_HISTORY) played.orEmpty() else emptyList()))
    val estimate = estimateUsd(seeds.size, avoidSongs.size + suggested.size, count)
    val fromLabel = when (basis) {
        ClaudeRecommend.BASIS_QUEUE -> fromQueue.format(seeds.size)
        ClaudeRecommend.BASIS_HISTORY -> fromHistory.format(seeds.size)
        else -> song.title
    }

    fun send() {
        if (sending || seeds.isEmpty()) return
        sending = true
        error = null
        val language = Locale.getDefault().toLanguageTag()
        val sentBasis = basis
        val sentMood = mood.trim()
        val sentFrom = fromLabel
        scope.launch {
            try {
                val outcome = ClaudeRecommend.recommend(
                    basis = sentBasis,
                    seeds = named(seeds),
                    avoid = named(avoidSongs) + suggested,
                    mood = sentMood,
                    count = count,
                    novelty = novelty,
                    language = language,
                )
                when (outcome) {
                    is ClaudeRecommend.Outcome.Failed ->
                        error = failedPrefix.format(failureLabels[outcome.failure ?: ClaudeFailure.ERROR].orEmpty())
                    is ClaudeRecommend.Outcome.Found -> {
                        suggested = suggested + outcome.picks.map { ClaudeRecommend.Named(it.song.title, it.song.artist) }
                        if (outcome.picks.isEmpty()) {
                            error = failedText
                        } else {
                            val entry = RecommendHistory.Entry(
                                at = System.currentTimeMillis(),
                                basis = sentBasis,
                                from = sentFrom,
                                mood = sentMood,
                                items = outcome.picks.map(RecommendHistory::itemOf),
                            )
                            runCatching { RecommendHistory.add(entry) }
                            shownEntry = entry
                            fresh = true
                            asked = outcome.asked
                            checked = entry.items.map { it.videoId }.toSet()
                            page = RecommendPage.RESULTS
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failedPrefix.format(failureLabels[ClaudeFailure.ERROR].orEmpty())
            } finally {
                sending = false
            }
        }
    }

    fun withQueue(action: ((List<Song>) -> Unit)?, songs: List<Song>) {
        if (action == null) {
            Toast.makeText(app, notReady, Toast.LENGTH_SHORT).show()
            return
        }
        action(songs)
        onDismiss()
    }

    val title = when (page) {
        RecommendPage.HISTORY -> stringResource(R.string.recommend_history)
        RecommendPage.ENTRY, RecommendPage.RESULTS -> shownEntry?.from ?: stringResource(R.string.recommend_title)
        else -> stringResource(R.string.recommend_title)
    }
    val hazeState = remember { HazeState() }
    val headers = RecommendHeaders(
        from = stringResource(R.string.recommend_from_header),
        count = stringResource(R.string.recommend_count_header),
        novelty = stringResource(R.string.recommend_novelty_header),
        results = stringResource(R.string.recommend_results_header),
    )
    KoWindow(
        title = title,
        onBack = {
            when {
                asking != null -> asking = null
                page == RecommendPage.ENTRY -> page = RecommendPage.HISTORY
                page != RecommendPage.SETUP -> page = RecommendPage.SETUP
                else -> onDismiss()
            }
        },
        overlay = {
            val ask = asking
            if (ask != null) {
                ConfirmationAlert(
                    hazeState = hazeState,
                    title = ask.title,
                    description = ask.description,
                    confirmLabel = stringResource(R.string.ko_delete),
                    onConfirm = {
                        asking = null
                        scope.launch {
                            try {
                                ask.run()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(app, app.getString(R.string.ko_delete_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { asking = null },
                    frosted = false,
                )
            }
        },
    ) {
        when (page) {
            RecommendPage.SETUP -> setupPage(
                song = song,
                basis = basis,
                onBasis = { basis = it },
                queueSize = queue.size,
                playedSize = played?.size,
                mood = mood,
                onMood = { mood = it.take(ClaudeRecommend.MAX_MOOD_CHARS) },
                count = count,
                onCount = { count = it },
                novelty = novelty,
                onNovelty = { novelty = it },
                estimate = format.money(estimate),
                sending = sending,
                canSend = seeds.isNotEmpty() && ClaudeRecommend.available,
                error = error,
                historySize = history?.size ?: 0,
                onSend = ::send,
                onHistory = { page = RecommendPage.HISTORY },
                headers = headers,
            )
            RecommendPage.RESULTS, RecommendPage.ENTRY -> {
                val entry = shownEntry ?: return@KoWindow
                resultsPage(
                    entry = entry,
                    fresh = fresh && page == RecommendPage.RESULTS,
                    asked = asked,
                    checked = checked,
                    onToggle = { id -> checked = if (id in checked) checked - id else checked + id },
                    onAll = { all -> checked = if (all) entry.items.map { it.videoId }.toSet() else emptySet() },
                    when_ = format.time(entry.at),
                    onPlay = {
                        val songs = entry.items.filter { it.videoId in checked }.map { it.song() }
                        val play = KoQueue.play
                        withQueue(play?.let { p -> { list: List<Song> -> p(list, 0) } }, songs)
                    },
                    onPlayNext = { withQueue(KoQueue.playNext, entry.items.filter { it.videoId in checked }.map { it.song() }) },
                    onAddToQueue = { withQueue(KoQueue.addToQueue, entry.items.filter { it.videoId in checked }.map { it.song() }) },
                    onSave = {
                        val songs = entry.items.filter { it.videoId in checked }.map { it.song() }
                        val save = KoQueue.savePlaylist
                        if (save == null) {
                            Toast.makeText(app, notReady, Toast.LENGTH_SHORT).show()
                        } else {
                            save(playlistName.format(entry.from), songs)
                        }
                    },
                    onAgain = if (page == RecommendPage.RESULTS && !sending) ({ page = RecommendPage.SETUP; send() }) else null,
                    onDelete = if (page == RecommendPage.ENTRY) {
                        {
                            asking = RecommendAsk(
                                app.getString(R.string.recommend_delete_entry),
                                app.getString(R.string.recommend_delete_entry_confirm),
                            ) {
                                RecommendHistory.remove(entry.at)
                                page = RecommendPage.HISTORY
                            }
                        }
                    } else {
                        null
                    },
                    headers = headers,
                )
            }
            RecommendPage.HISTORY -> historyPage(
                entries = history.orEmpty(),
                format = format,
                onOpen = { entry ->
                    shownEntry = entry
                    fresh = false
                    checked = entry.items.map { it.videoId }.toSet()
                    page = RecommendPage.ENTRY
                },
                onClear = {
                    asking = RecommendAsk(
                        app.getString(R.string.recommend_clear_history),
                        app.getString(R.string.recommend_clear_history_confirm, history?.size ?: 0),
                    ) { RecommendHistory.clear() }
                },
            )
        }
    }
}

/** Card headers, read where strings can be read: the list builders below can't. */
private class RecommendHeaders(val from: String, val count: String, val novelty: String, val results: String)

private const val QUEUE_SEEDS = 20
private const val HISTORY_SEEDS = 20
private const val AVOID_FROM_QUEUE = 60

private fun androidx.compose.foundation.lazy.LazyListScope.setupPage(
    song: Song,
    basis: String,
    onBasis: (String) -> Unit,
    queueSize: Int,
    playedSize: Int?,
    mood: String,
    onMood: (String) -> Unit,
    count: Int,
    onCount: (Int) -> Unit,
    novelty: String,
    onNovelty: (String) -> Unit,
    estimate: String,
    sending: Boolean,
    canSend: Boolean,
    error: String?,
    historySize: Int,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    headers: RecommendHeaders,
) {
    if (!ClaudeRecommend.available) {
        item(key = "unavailable") {
            Text(
                text = stringResource(R.string.recommend_unavailable),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    val bases = listOf(ClaudeRecommend.BASIS_SONG, ClaudeRecommend.BASIS_QUEUE, ClaudeRecommend.BASIS_HISTORY)
    windowCard(rows = bases, header = headers.from) { option ->
        val label = when (option) {
            ClaudeRecommend.BASIS_QUEUE -> stringResource(R.string.recommend_basis_queue, queueSize)
            ClaudeRecommend.BASIS_HISTORY -> stringResource(R.string.recommend_basis_history)
            else -> stringResource(R.string.recommend_basis_song, song.title)
        }
        val detail = when (option) {
            ClaudeRecommend.BASIS_QUEUE -> if (queueSize == 0) stringResource(R.string.recommend_queue_empty) else null
            ClaudeRecommend.BASIS_HISTORY -> when {
                playedSize == null -> null
                playedSize == 0 -> stringResource(R.string.recommend_history_empty_seed)
                else -> stringResource(R.string.recommend_basis_history_detail, playedSize)
            }
            else -> song.artist.takeIf { it.isNotBlank() }
        }
        val enabled = when (option) {
            ClaudeRecommend.BASIS_QUEUE -> queueSize > 0
            ClaudeRecommend.BASIS_HISTORY -> (playedSize ?: 0) > 0
            else -> true
        }
        WindowChoice(label = label, checked = option == basis, detail = detail, enabled = enabled) { onBasis(option) }
    }
    windowCard(rows = listOf(Unit), header = null) {
        val hint = stringResource(R.string.recommend_mood_hint)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.recommend_mood_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(11.dp))
                    .padding(12.dp),
            ) {
                if (mood.isEmpty()) {
                    Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = mood,
                    onValueChange = onMood,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    windowCard(rows = listOf(10, 20, 30), header = headers.count) { option ->
        WindowChoice(label = stringResource(R.string.recommend_count_option, option), checked = option == count) { onCount(option) }
    }
    val novelties = listOf(ClaudeRecommend.NOVELTY_FAMILIAR, ClaudeRecommend.NOVELTY_MIXED, ClaudeRecommend.NOVELTY_NEW)
    windowCard(rows = novelties, header = headers.novelty) { option ->
        val label = stringResource(
            when (option) {
                ClaudeRecommend.NOVELTY_FAMILIAR -> R.string.recommend_novelty_familiar
                ClaudeRecommend.NOVELTY_NEW -> R.string.recommend_novelty_new
                else -> R.string.recommend_novelty_mixed
            },
        )
        WindowChoice(label = label, checked = option == novelty) { onNovelty(option) }
    }
    item(key = "estimate") {
        Text(
            text = stringResource(R.string.recommend_estimate, estimate),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (error != null) {
        windowText(error, error = true)
    }
    windowCard(rows = listOf(Unit), header = null) {
        WindowAction(
            label = stringResource(if (sending) R.string.recommend_sending else R.string.recommend_send),
            enabled = canSend && !sending,
            onClick = onSend,
        )
    }
    windowCard(rows = listOf(Unit), header = null) {
        WindowLine(
            title = stringResource(R.string.recommend_history),
            value = stringResource(R.string.recommend_history_count, historySize),
            onClick = onHistory,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultsPage(
    entry: RecommendHistory.Entry,
    fresh: Boolean,
    asked: Int,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onAll: (Boolean) -> Unit,
    when_: String,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSave: () -> Unit,
    onAgain: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    headers: RecommendHeaders,
) {
    item(key = "summary") {
        val lines = listOfNotNull(
            if (fresh) stringResource(R.string.recommend_found, entry.items.size, asked) else when_,
            entry.mood.takeIf { it.isNotBlank() }?.let { "“$it”" },
            stringResource(R.string.recommend_selected, checked.size),
        )
        Text(
            text = lines.joinToString("\n"),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val any = checked.isNotEmpty()
    windowCard(rows = listOf("play", "next", "queue", "save"), header = null) { action ->
        when (action) {
            "play" -> WindowAction(label = stringResource(R.string.recommend_play), enabled = any, onClick = onPlay)
            "next" -> WindowAction(label = stringResource(R.string.recommend_play_next), enabled = any, onClick = onPlayNext)
            "queue" -> WindowAction(label = stringResource(R.string.recommend_add_queue), enabled = any, onClick = onAddToQueue)
            else -> WindowAction(label = stringResource(R.string.recommend_save_playlist), enabled = any, onClick = onSave)
        }
    }
    windowCard(rows = entry.items, header = headers.results, key = { "song-" + it.videoId }) { item ->
        WindowChoice(
            label = item.title,
            checked = item.videoId in checked,
            detail = listOf(item.artist, item.reason).filter { it.isNotBlank() }.joinToString(" · "),
        ) { onToggle(item.videoId) }
    }
    windowCard(rows = listOfNotNull("all", "none", "again".takeIf { onAgain != null }, "delete".takeIf { onDelete != null }), header = null) { action ->
        when (action) {
            "all" -> WindowAction(label = stringResource(R.string.recommend_select_all), onClick = { onAll(true) })
            "none" -> WindowAction(label = stringResource(R.string.recommend_select_none), onClick = { onAll(false) })
            "again" -> WindowAction(label = stringResource(R.string.recommend_again), onClick = { onAgain?.invoke() })
            else -> WindowAction(label = stringResource(R.string.recommend_delete_entry), destructive = true, onClick = { onDelete?.invoke() })
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyPage(
    entries: List<RecommendHistory.Entry>,
    format: KoFormat,
    onOpen: (RecommendHistory.Entry) -> Unit,
    onClear: () -> Unit,
) {
    if (entries.isEmpty()) {
        item(key = "empty") {
            Text(
                text = stringResource(R.string.recommend_history_empty),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        windowCard(rows = entries, header = null, key = { "entry-" + it.at }) { entry ->
            WindowLine(
                title = entry.from,
                detail = listOfNotNull(format.time(entry.at), entry.mood.takeIf { it.isNotBlank() }).joinToString(" · "),
                value = entry.items.size.toString(),
                onClick = { onOpen(entry) },
            )
        }
        windowCard(rows = listOf(Unit), header = null) {
            WindowAction(label = stringResource(R.string.recommend_clear_history), destructive = true, onClick = onClear)
        }
    }
    item(key = "footer") {
        Text(
            text = stringResource(R.string.recommend_history_footer),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
