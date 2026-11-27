package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.LyricsTranslation.StoredAnswer
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.TranslateAhead
import com.music.bitchord.download.TranslationStatus
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.screens.claudeModeLabelRes
import com.music.bitchord.ui.screens.claudeSpeechLabelRes
import com.music.bitchord.ui.screens.megabytes
import dev.chrisbanes.haze.HazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The words every status screen needs, resolved once in composition: the
 * loops that build the lines are not where composable calls belong.
 */
private class StatusWords(
    val states: Map<TranslationStatus.State, String>,
    val kinds: Map<String, String>,
    val vias: Map<String, String>,
    val kept: String,
    val cache: String,
    val haiku: String,
    val sonnet: String,
    val google: String,
    val speech: Map<String, String>,
    val mode: Map<String, String>,
    val badges: Map<TranslationStatus.State, WindowBadge>,
    val format: KoFormat,
) {
    fun language(tag: String): String =
        Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.getDefault()).ifBlank { tag }

    /** Who wrote [answer] and where it is kept: "Claude Haiku · 보관함". */
    fun engine(answer: StoredAnswer): String = listOf(
        when {
            !answer.byClaude -> google
            answer.model == LyricsTranslation.CLAUDE_SONNET -> sonnet
            answer.model == LyricsTranslation.CLAUDE_HAIKU -> haiku
            else -> "Claude"
        },
        if (answer.kept) kept else cache,
    ).joinToString(" · ")

    /** In what and how it came: "한국어 · 자동 · 자연스럽게 · 미리 번역 · 11월 17일". */
    fun style(answer: StoredAnswer): String = buildList {
        add(language(answer.target))
        answer.speech?.takeIf { it.isNotEmpty() }?.let { add(speech[it] ?: it) }
        answer.mode?.takeIf { it.isNotEmpty() }?.let { add(mode[it] ?: it) }
        answer.via?.let { add(vias[it] ?: it) }
        if (answer.at > 0) add(format.time(answer.at, withTime = false))
    }.joinToString(" · ")
}

@Composable
private fun statusWords(): StatusWords {
    val styleSpeech = listOf(LyricsTranslation.SPEECH_AUTO, LyricsTranslation.SPEECH_CASUAL, LyricsTranslation.SPEECH_POLITE)
    val styleMode = listOf(LyricsTranslation.MODE_NATURAL, LyricsTranslation.MODE_LITERAL)
    return StatusWords(
        states = mapOf(
            TranslationStatus.State.CLAUDE to stringResource(R.string.translation_status_claude),
            TranslationStatus.State.CLAUDE_OTHER_STYLE to stringResource(R.string.translation_status_claude_other),
            TranslationStatus.State.GOOGLE to stringResource(R.string.translation_status_google),
            TranslationStatus.State.SAME_LANGUAGE to stringResource(R.string.translation_status_same),
            TranslationStatus.State.NO_LYRICS to stringResource(R.string.translation_status_no_lyrics),
            TranslationStatus.State.NONE to stringResource(R.string.translation_status_none),
        ),
        kinds = mapOf(
            LyricsTranslation.KIND_TRANSLATION to stringResource(R.string.translation_kind_translation),
            LyricsTranslation.KIND_READING to stringResource(R.string.translation_kind_reading),
            LyricsTranslation.KIND_MEANING to stringResource(R.string.translation_kind_meaning),
            LyricsTranslation.KIND_FURIGANA to stringResource(R.string.translation_kind_furigana),
        ),
        vias = mapOf(
            LyricsTranslation.VIA_PLAYER to stringResource(R.string.translation_via_player),
            LyricsTranslation.VIA_AHEAD to stringResource(R.string.translation_via_ahead),
            LyricsTranslation.VIA_DOWNLOAD to stringResource(R.string.translation_via_download),
            LyricsTranslation.VIA_RESTORE to stringResource(R.string.translation_via_restore),
        ),
        kept = stringResource(R.string.translation_store_kept),
        cache = stringResource(R.string.translation_store_cache),
        haiku = stringResource(R.string.claude_model_haiku),
        sonnet = stringResource(R.string.claude_model_sonnet),
        google = stringResource(R.string.translation_engine_google),
        speech = styleSpeech.associateWith { stringResource(claudeSpeechLabelRes(it)) },
        mode = styleMode.associateWith { stringResource(claudeModeLabelRes(it)) },
        badges = mapOf(
            TranslationStatus.State.CLAUDE to WindowBadge(stringResource(R.string.translation_badge_claude), Color(0xFF34C759)),
            TranslationStatus.State.CLAUDE_OTHER_STYLE to WindowBadge(stringResource(R.string.translation_badge_other), Color(0xFFFF9500)),
            TranslationStatus.State.GOOGLE to WindowBadge(stringResource(R.string.translation_badge_google), Color(0xFF8E8E93)),
            TranslationStatus.State.SAME_LANGUAGE to WindowBadge(stringResource(R.string.translation_badge_same), Color(0xFF0A84FF)),
            TranslationStatus.State.NO_LYRICS to WindowBadge(
                stringResource(R.string.translation_badge_no_lyrics),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
            TranslationStatus.State.NONE to WindowBadge(
                stringResource(R.string.translation_badge_none),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        ),
        format = koFormat(),
    )
}

/**
 * The song menu's "Translation status" row: where this song stands, on the
 * row itself, and its stored answers in full on a tap.
 */
@Composable
internal fun TranslationStatusRow(song: Song, accent: Color, onOpen: () -> Unit) {
    val context = LocalContext.current
    var status by remember(song.videoId) { mutableStateOf<TranslationStatus.SongStatus?>(null) }
    LaunchedEffect(song.videoId) {
        TranslationStatus.remember(song.videoId, song.title, song.artist)
        // Unreadable: the row just shows no status.
        status = orNull { TranslationStatus.statusOf(context, listOf(song.videoId)) }?.get(song.videoId)
    }
    val words = statusWords()
    val current = status
    val value = current?.let { words.states[it.state] }
    val detail = current?.let { now ->
        buildList {
            now.translation?.let { add(if (it.kept) words.kept else words.cache) }
            if (now.reading != null) add(words.kinds[LyricsTranslation.KIND_READING] + " ✓")
            if (now.meaning != null) add(words.kinds[LyricsTranslation.KIND_MEANING] + " ✓")
            if (now.furigana != null) add(words.kinds[LyricsTranslation.KIND_FURIGANA] + " ✓")
        }.joinToString(" · ").ifBlank { null }
    }
    ActionRow(
        icon = SfIcons.Translate,
        label = stringResource(R.string.translation_status),
        value = value,
        accent = accent,
        description = detail,
        onClick = onOpen,
    )
}

/**
 * The translation status screens, shown by flags in [KoAlerts]: the song
 * menu's one song as an alert, and a release's songs and Settings' storage
 * page as windows of their own, which have room for every song.
 */
@Composable
fun TranslationStatusAlerts(hazeState: HazeState) {
    val song by KoAlerts.songStatus.collectAsStateWithLifecycle()
    song?.let { shown ->
        BackHandler { KoAlerts.songStatus.value = null }
        SongStatusAlert(hazeState = hazeState, song = shown, onBack = { KoAlerts.songStatus.value = null })
    }
    val release by KoAlerts.releaseStatus.collectAsStateWithLifecycle()
    release?.let { shown ->
        ReleaseStatusWindow(hazeState = hazeState, release = shown, onDismiss = { KoAlerts.releaseStatus.value = null })
    }
    val storage by KoAlerts.storage.collectAsStateWithLifecycle()
    if (storage) {
        StorageWindow(hazeState = hazeState, onDismiss = { KoAlerts.storage.value = false })
    }
}

/**
 * [block]'s answer, or null when it failed: a store that cannot be read is
 * said so on the screen with a way to try again, not a crash or a spinner
 * that never ends. Being cancelled is not failing.
 */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** A song's status as it is being read: null [status] while it is, or when it could not be ([failed]). */
private class SongLoad(val status: TranslationStatus.SongStatus?, val failed: Boolean)

/** [song]'s status, looked up again whenever another song is asked for, or [reload] changes. */
@Composable
private fun rememberSongStatus(song: Song?, reload: Int = 0): SongLoad {
    val context = LocalContext.current
    var status by remember(song?.videoId) { mutableStateOf<TranslationStatus.SongStatus?>(null) }
    var failed by remember(song?.videoId) { mutableStateOf(false) }
    LaunchedEffect(song?.videoId, reload) {
        val id = song?.videoId ?: return@LaunchedEffect
        failed = false
        val read = orNull { TranslationStatus.statusOf(context, listOf(id)) }
        failed = read == null
        status = read?.get(id) ?: status
    }
    return SongLoad(status, failed)
}

/** A delete waiting on its warning's answer: what to say, and what to do on yes. */
private class DeleteAsk(
    val title: String,
    val description: String,
    val run: suspend () -> LyricsTranslation.Cleared,
    val then: (LyricsTranslation.Cleared) -> Unit = {},
)

/**
 * Where a confirmed delete runs: not in the warning's own scope, which goes
 * as the warning closes — on the same tap that started it.
 */
private val deleteScope = kotlinx.coroutines.MainScope()

/** The warning before [ask] runs, drawn in a window; null shows nothing. */
@Composable
private fun DeleteWarning(hazeState: HazeState, ask: DeleteAsk?, frosted: Boolean, onDone: () -> Unit) {
    if (ask == null) return
    val context = LocalContext.current.applicationContext
    val failedText = stringResource(R.string.ko_delete_failed)
    ConfirmationAlert(
        hazeState = hazeState,
        title = ask.title,
        description = ask.description,
        confirmLabel = stringResource(R.string.ko_delete),
        onConfirm = {
            onDone()
            deleteScope.launch {
                val cleared = orNull { ask.run() }
                if (cleared != null) ask.then(cleared) else Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
            }
        },
        onDismiss = onDone,
        frosted = frosted,
    )
}

/** Asks before deleting everything stored for [song]. */
@Composable
private fun songDeleteAsk(song: Song, count: Int, then: () -> Unit): DeleteAsk {
    val context = LocalContext.current
    val title = stringResource(R.string.translation_song_delete)
    val description = stringResource(R.string.translation_song_delete_confirm, song.title, count)
    val done = stringResource(R.string.translation_song_deleted)
    return DeleteAsk(
        title = title,
        description = description,
        run = { LyricsTranslation.deleteTrack(song.videoId, listOf(TranslationStatus.target(context))) },
        then = { cleared ->
            Toast.makeText(context, done.format(cleared.files), Toast.LENGTH_SHORT).show()
            then()
        },
    )
}

/** The words a song's own page shows above its answers: who sings it, where it stands. */
private class SongPageWords(
    val nothing: String,
    val otherNote: String,
    val noLyricsNote: String,
    val lookAgain: String,
    val lookAgainDone: String,
    val loadFailed: String,
    val retry: String,
    /** The song's status could not be read: say so, with a way to try again. */
    val failed: Boolean = false,
) {
    fun failing(failed: Boolean) = SongPageWords(nothing, otherNote, noLyricsNote, lookAgain, lookAgainDone, loadFailed, retry, failed)
}

@Composable
private fun songPageWords() = SongPageWords(
    nothing = stringResource(R.string.translation_status_nothing),
    otherNote = stringResource(R.string.translation_status_other_note),
    noLyricsNote = stringResource(R.string.translation_status_no_lyrics_note),
    lookAgain = stringResource(R.string.translation_status_look_again),
    lookAgainDone = stringResource(R.string.translation_status_look_again_done),
    loadFailed = stringResource(R.string.ko_load_failed),
    retry = stringResource(R.string.ko_retry),
)

private fun songHeading(song: Song, status: TranslationStatus.SongStatus?, words: StatusWords, page: SongPageWords): String =
    listOfNotNull(
        song.artist.takeIf { it.isNotBlank() },
        status?.let { words.states[it.state] },
        page.otherNote.takeIf { status?.state == TranslationStatus.State.CLAUDE_OTHER_STYLE },
    ).joinToString("\n")

/** One answer to a pair of lines: what it is and who wrote it, then in what style and how it came. */
private fun songLines(status: TranslationStatus.SongStatus?, words: StatusWords): List<Pair<String, String>> =
    status?.answers.orEmpty()
        .sortedWith(compareBy<StoredAnswer> { it.kind }.thenByDescending { it.at })
        .map { answer -> ((words.kinds[answer.kind] ?: answer.kind) + " · " + words.engine(answer)) to words.style(answer) }

/** Every stored answer of one song, each with who wrote it, in which style, and where it is kept. */
@Composable
private fun SongStatusAlert(hazeState: HazeState, song: Song, onBack: () -> Unit) {
    var reload by remember(song.videoId) { mutableIntStateOf(0) }
    var asking by remember(song.videoId) { mutableStateOf(false) }
    val load = rememberSongStatus(song, reload)
    val current = load.status
    val loadFailed = stringResource(R.string.ko_load_failed)
    val retryLabel = stringResource(R.string.ko_retry)
    val ask = songDeleteAsk(song, current?.answers?.size ?: 0) { reload++ }
    if (asking) {
        DeleteWarning(hazeState, ask, frosted = true) { asking = false }
        return
    }
    val words = statusWords()
    val page = songPageWords()
    val heading = songHeading(song, current, words, page)
    val lines = songLines(current, words).map { (kind, detail) -> kind + "\n   " + detail }
    AlertScaffold(hazeState = hazeState, onDismiss = onBack) {
        AlertHeading(title = song.title, message = heading)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            val shown = when {
                current == null && load.failed -> listOf(loadFailed)
                current != null && lines.isEmpty() -> listOf(page.nothing)
                else -> lines
            }
            shown.forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (current == null && load.failed) {
            AlertRule()
            AlertAction(label = retryLabel, emphasised = true, onClick = { reload++ })
        }
        if (!current?.answers.isNullOrEmpty()) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translation_song_delete),
                emphasised = false,
                destructive = true,
                onClick = { asking = true },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.back), emphasised = false, onClick = onBack)
    }
}

/** The same song, as a page inside a window: its standing, then each stored answer as a row. */
private fun LazyListScope.songStatusItems(
    song: Song,
    status: TranslationStatus.SongStatus?,
    words: StatusWords,
    page: SongPageWords,
    loading: String,
    deleteLabel: String,
    onLookAgain: () -> Unit,
    onDelete: () -> Unit,
) {
    val heading = songHeading(song, status, words, page)
    if (status == null && page.failed) {
        windowError(page.loadFailed, page.retry, onRetry = onLookAgain)
        return
    }
    windowText(if (status == null) loading else heading.ifBlank { song.title })
    if (status == null) return
    if (status.state == TranslationStatus.State.NO_LYRICS) {
        windowText(page.noLyricsNote)
        windowActions(
            listOf(
                WindowButton(label = page.lookAgain) {
                    com.music.bitchord.download.PlayedLyrics.clearMissing(song.videoId)
                    onLookAgain()
                },
            ),
        )
        return
    }
    val lines = songLines(status, words)
    if (lines.isEmpty()) {
        windowText(page.nothing)
    } else {
        windowCard(rows = lines) { (kind, detail) -> WindowLine(title = kind, detail = detail) }
        windowActions(listOf(WindowButton(label = deleteLabel, destructive = true, onClick = onDelete)))
    }
}

/**
 * A playlist's or an album's songs, each with where it stands, and the
 * missing ones sent ahead at the batch price. A song opens its own page.
 */
@Composable
private fun ReleaseStatusWindow(hazeState: HazeState, release: AheadAsk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val songs = remember(release) { release.songs.distinctBy { it.videoId } }
    var statuses by remember(release) { mutableStateOf<Map<String, TranslationStatus.SongStatus>?>(null) }
    var reload by remember(release) { mutableIntStateOf(0) }
    var asking by remember(release) { mutableStateOf<DeleteAsk?>(null) }
    var failed by remember(release) { mutableStateOf(false) }
    LaunchedEffect(release, reload) {
        songs.forEach { TranslationStatus.remember(it.videoId, it.title, it.artist) }
        failed = false
        val read = orNull { TranslationStatus.statusOf(context, songs.map { it.videoId }) }
        failed = read == null
        statuses = read ?: statuses
    }
    var opened by remember(release) { mutableStateOf<Song?>(null) }
    val openedLoad = rememberSongStatus(opened, reload)
    val openedStatus = openedLoad.status
    val deleteLabel = stringResource(R.string.translation_song_delete)
    val openedAsk = opened?.let { song -> songDeleteAsk(song, openedStatus?.answers?.size ?: 0) { reload++ } }
    val words = statusWords()
    val page = songPageWords()
    val all = statuses
    val counts = TranslationStatus.State.entries.associateWith { state -> all?.values?.count { it.state == state } ?: 0 }
    val keptCount = all?.values?.count { it.covered && it.translation?.kept == true } ?: 0
    val cacheCount = all?.values?.count { it.covered && it.translation?.kept == false } ?: 0
    val missing = songs.filter { all?.get(it.videoId)?.covered == false }
    val style = listOf(
        words.language(TranslationStatus.target(context)),
        words.speech[LyricsTranslation.translationSpeech] ?: LyricsTranslation.translationSpeech,
        words.mode[LyricsTranslation.translationMode] ?: LyricsTranslation.translationMode,
    ).joinToString(" · ")
    val loading = stringResource(R.string.translation_status_loading)
    // The style the songs are judged in, then a row per state with its count: easier to read than one long line.
    val summary = if (all == null) {
        loading
    } else {
        listOf(
            style,
            stringResource(R.string.translation_status_stores, keptCount, cacheCount),
        ).joinToString("\n")
    }
    val songsCount = stringResource(R.string.translation_status_count)
    val stateRows = TranslationStatus.State.entries.filter { (counts[it] ?: 0) > 0 }
    val note = stringResource(R.string.translation_status_note)
    val summaryHeader = stringResource(R.string.ko_window_summary)
    val songsHeader = stringResource(R.string.ko_window_songs, songs.size)
    val translateMissing = stringResource(R.string.translation_status_translate_missing, missing.size)
    val buttons = if (missing.isNotEmpty() && TranslateAhead.available()) {
        listOf(
            WindowButton(label = translateMissing) {
                onDismiss()
                requestTranslateAhead(context, release.title, missing, release.browseId, release.songs)
            },
        )
    } else {
        emptyList()
    }
    val mainState = rememberLazyListState()
    val songState = remember(opened?.videoId) { LazyListState() }
    val inner = opened
    KoWindow(
        title = inner?.title ?: release.title,
        onBack = {
            when {
                asking != null -> asking = null
                opened != null -> opened = null
                else -> onDismiss()
            }
        },
        listState = if (inner != null) songState else mainState,
        overlay = { DeleteWarning(hazeState, asking, frosted = false) { asking = null } },
    ) {
        if (inner != null) {
            songStatusItems(inner, openedStatus, words, page.failing(openedLoad.failed), loading, deleteLabel, onLookAgain = { reload++ }) { asking = openedAsk }
            return@KoWindow
        }
        if (failed && all == null) {
            windowError(page.loadFailed, page.retry, header = summaryHeader) { reload++ }
            return@KoWindow
        }
        windowText(summary, header = summaryHeader)
        if (stateRows.isNotEmpty()) {
            windowCard(rows = stateRows, footer = note) { state ->
                WindowLine(
                    title = words.states[state].orEmpty(),
                    value = songsCount.format(counts[state] ?: 0),
                    badge = words.badges[state],
                )
            }
        }
        windowActions(buttons)
        if (all != null) {
            windowCard(rows = songs, header = songsHeader, key = { it.videoId }) { song ->
                val status = all[song.videoId]
                val state = status?.state ?: TranslationStatus.State.NONE
                val detail = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    status?.translation?.takeIf { status.covered || state == TranslationStatus.State.GOOGLE }
                        ?.let { if (it.kept) words.kept else words.cache },
                    status?.reading?.let { words.kinds[LyricsTranslation.KIND_READING] + " ✓" },
                    status?.furigana?.let { words.kinds[LyricsTranslation.KIND_FURIGANA] + " ✓" },
                ).joinToString(" · ")
                WindowLine(
                    title = song.title,
                    detail = detail,
                    badge = words.badges[state],
                    onClick = { opened = song },
                )
            }
        }
    }
}

/**
 * Every song with something stored, searchable; what it all takes up, by
 * kind and by place; the kept store's limit; and the cache's clearing.
 */
@Composable
private fun StorageWindow(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf<List<StoredAnswer>?>(null) }
    var breakdown by remember { mutableStateOf<LyricsTranslation.StorageBreakdown?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<DeleteAsk?>(null) }
    var opened by remember { mutableStateOf<Song?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        failed = false
        val readAnswers = orNull { LyricsTranslation.storedAnswers() }
        val readBreakdown = orNull { LyricsTranslation.storageBreakdown() }
        failed = readAnswers == null || readBreakdown == null
        answers = readAnswers ?: answers
        breakdown = readBreakdown ?: breakdown
    }
    val openedLoad = rememberSongStatus(opened, reload)
    val openedStatus = openedLoad.status
    val deleteLabel = stringResource(R.string.translation_song_delete)
    val openedAsk = opened?.let { song -> songDeleteAsk(song, openedStatus?.answers?.size ?: 0) { reload++ } }
    val keptLimitMb by AppSettings.keptStoreLimitMb.collectAsStateWithLifecycle()
    val words = statusWords()
    val page = songPageWords()
    val all = answers
    val clearedText = stringResource(R.string.translation_storage_cleared)
    val deleteFailed = stringResource(R.string.ko_delete_failed)

    // One row per song, newest first: the answers of a song together.
    val songs = remember(all) {
        all.orEmpty().groupBy { it.trackId }.values.sortedByDescending { group -> group.maxOf { it.at } }
    }
    val shown = remember(songs, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            songs
        } else {
            songs.filter { group ->
                group.any {
                    it.title?.contains(needle, ignoreCase = true) == true ||
                        it.artist?.contains(needle, ignoreCase = true) == true ||
                        it.trackId.contains(needle)
                }
            }
        }
    }
    val parts = breakdown
    val cacheLimit = LyricsTranslation.CACHE_LIMIT_BYTES
    val loading = stringResource(R.string.translation_status_loading)
    // Each place with how full it is, each kind with its songs and size: a row apiece.
    val mb = 1024L * 1024L
    val usedOf = stringResource(R.string.translation_storage_used)
    val songsSize = stringResource(R.string.translation_storage_songs_size)
    val meters = if (parts == null) {
        emptyList()
    } else {
        listOf(
            Triple(words.kept, usedOf.format(megabytes(parts.keptBytes), keptLimitMb), parts.keptBytes.toFloat() / (keptLimitMb * mb)),
            Triple(words.cache, usedOf.format(megabytes(parts.cacheBytes), cacheLimit / mb), parts.cacheBytes.toFloat() / cacheLimit),
        )
    }
    val kinds = if (parts == null) {
        emptyList()
    } else {
        listOfNotNull(
            stringResource(R.string.translation_storage_kind_claude) to parts.claude,
            stringResource(R.string.translation_storage_kind_google) to parts.google,
            stringResource(R.string.translation_storage_kind_reading) to parts.reading,
            stringResource(R.string.translation_storage_kind_meaning) to parts.meaning,
            (stringResource(R.string.translation_storage_kind_other) to parts.other).takeIf { parts.other.songs > 0 },
        ).map { (label, part) -> label to songsSize.format(part.songs, megabytes(part.bytes)) }
    }
    val warnings = if (parts == null) {
        null
    } else {
        listOfNotNull(
            stringResource(R.string.translation_storage_cache_full).takeIf { parts.cacheBytes * 10 >= cacheLimit * 8 },
            stringResource(R.string.translation_storage_kept_full)
                .takeIf { parts.keptBytes * 10 >= keptLimitMb * mb * 8 },
        ).joinToString("\n").ifBlank { null }
    }
    val kindsHeader = stringResource(R.string.translation_storage_kinds_header)
    val unnamed = stringResource(R.string.translation_storage_unnamed)
    val spaceHeader = stringResource(R.string.ko_window_space)
    val limitHeader = stringResource(R.string.translation_storage_limit)
    val limitFooter = stringResource(R.string.translation_storage_limit_description)
    val manageHeader = stringResource(R.string.ko_window_manage)
    val songsHeader = stringResource(R.string.ko_window_songs, shown.size)
    val searchHint = stringResource(R.string.translation_storage_search)
    val noMatch = stringResource(R.string.ko_window_no_match)
    val limits = listOf(100, 200, 500)
    val limitChosen = limits.minByOrNull { kotlin.math.abs(it - keptLimitMb) } ?: 100
    val report: (LyricsTranslation.Cleared) -> Unit = { cleared ->
        Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
        reload++
    }
    val clearTitle = stringResource(R.string.translation_storage_clear_all)
    val clearDescription = stringResource(
        if (AppSettings.keepClaudeTranslations.value) {
            R.string.translation_storage_clear_all_confirm
        } else {
            R.string.translation_storage_clear_all_confirm_claude
        },
    )
    val cacheAsk = DeleteAsk(clearTitle, clearDescription, { LyricsTranslation.clearCache(googleOnly = false) }, report)
    val keptAsk = DeleteAsk(
        title = stringResource(R.string.translation_storage_clear_kept),
        description = stringResource(R.string.translation_storage_clear_kept_confirm, megabytes(parts?.keptBytes ?: 0L)),
        run = { LyricsTranslation.clearKept() },
        then = report,
    )
    val meaningsAsk = DeleteAsk(
        title = stringResource(R.string.translation_storage_clear_meanings),
        description = stringResource(
            R.string.translation_storage_clear_meanings_confirm,
            parts?.meaning?.songs ?: 0,
            megabytes(parts?.meaning?.bytes ?: 0L),
        ),
        run = { LyricsTranslation.clearMeanings() },
        then = report,
    )
    val manageFooter = stringResource(R.string.translation_storage_manage_footer)
    val buttons = listOf(
        WindowButton(label = stringResource(R.string.translation_storage_clear_google)) {
            scope.launch {
                val cleared = orNull { LyricsTranslation.clearCache(googleOnly = true) }
                if (cleared == null) {
                    Toast.makeText(context, deleteFailed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
                }
                reload++
            }
        },
        WindowButton(label = stringResource(R.string.translation_storage_clear_all), destructive = true) {
            asking = cacheAsk
        },
        WindowButton(label = stringResource(R.string.translation_storage_clear_kept), destructive = true) {
            asking = keptAsk
        },
        WindowButton(label = stringResource(R.string.translation_storage_clear_meanings), destructive = true) {
            asking = meaningsAsk
        },
    )
    val mainState = rememberLazyListState()
    val songState = remember(opened?.videoId) { LazyListState() }
    val inner = opened
    KoWindow(
        title = inner?.title ?: stringResource(R.string.translation_storage),
        onBack = {
            when {
                asking != null -> asking = null
                opened != null -> opened = null
                else -> onDismiss()
            }
        },
        listState = if (inner != null) songState else mainState,
        overlay = { DeleteWarning(hazeState, asking, frosted = false) { asking = null } },
    ) {
        if (inner != null) {
            songStatusItems(inner, openedStatus, words, page.failing(openedLoad.failed), loading, deleteLabel, onLookAgain = { reload++ }) { asking = openedAsk }
            return@KoWindow
        }
        if (failed && (all == null || parts == null)) {
            windowError(page.loadFailed, page.retry, header = spaceHeader) { reload++ }
        } else if (all == null || parts == null) {
            windowText(loading, header = spaceHeader)
        } else {
            windowCard(rows = meters, header = spaceHeader, footer = warnings) { (label, value, fraction) ->
                WindowMeter(label = label, value = value, fraction = fraction)
            }
            windowCard(rows = kinds, header = kindsHeader) { (label, value) -> WindowLine(title = label, value = value) }
        }
        windowCard(rows = limits, header = limitHeader, footer = limitFooter) { limit ->
            WindowChoice(label = "$limit MB", checked = limit == limitChosen) { AppSettings.setKeptStoreLimitMb(limit) }
        }
        windowActions(buttons, header = manageHeader, footer = manageFooter)
        if (all == null) return@KoWindow
        if (songs.isNotEmpty()) {
            item(key = "search") {
                PillTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = searchHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            if (query.isNotBlank()) windowText(noMatch)
            return@KoWindow
        }
        windowCard(rows = shown, header = songsHeader, key = { it.first().trackId }) { group ->
            val first = group.first()
            val title = group.firstNotNullOfOrNull { it.title } ?: unnamed
            val artist = group.firstNotNullOfOrNull { it.artist }.orEmpty()
            val detail = group
                .sortedBy { it.kind }
                .joinToString(" · ") { answer ->
                    (words.kinds[answer.kind] ?: answer.kind) + " " +
                        (if (answer.byClaude) "Claude" else words.google) + " " +
                        (if (answer.kept) words.kept else words.cache)
                }
            WindowLine(
                title = if (artist.isBlank()) title else "$title — $artist",
                detail = detail,
                onClick = { opened = Song(videoId = first.trackId, title = title, artist = artist, thumbnailUrl = null) },
            )
        }
    }
}
