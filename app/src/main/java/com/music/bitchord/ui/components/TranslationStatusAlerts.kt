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
) {
    private val time = SimpleDateFormat("MM-dd", Locale.US)

    fun language(tag: String): String =
        Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.getDefault()).ifBlank { tag }

    /** "Claude Haiku · Korean · Auto · Natural · saved · translated ahead · 11-17" */
    fun describe(answer: StoredAnswer): String = buildList {
        add(
            when {
                !answer.byClaude -> google
                answer.model == LyricsTranslation.CLAUDE_SONNET -> sonnet
                answer.model == LyricsTranslation.CLAUDE_HAIKU -> haiku
                else -> "Claude"
            },
        )
        add(language(answer.target))
        answer.speech?.takeIf { it.isNotEmpty() }?.let { add(speech[it] ?: it) }
        answer.mode?.takeIf { it.isNotEmpty() }?.let { add(mode[it] ?: it) }
        add(if (answer.kept) kept else cache)
        answer.via?.let { add(vias[it] ?: it) }
        if (answer.at > 0) add(time.format(Date(answer.at)))
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
            TranslationStatus.State.NONE to stringResource(R.string.translation_status_none),
        ),
        kinds = mapOf(
            LyricsTranslation.KIND_TRANSLATION to stringResource(R.string.translation_kind_translation),
            LyricsTranslation.KIND_READING to stringResource(R.string.translation_kind_reading),
            LyricsTranslation.KIND_MEANING to stringResource(R.string.translation_kind_meaning),
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
    )
}

private fun mark(state: TranslationStatus.State): String = when (state) {
    TranslationStatus.State.CLAUDE -> "✓"
    TranslationStatus.State.CLAUDE_OTHER_STYLE -> "~"
    TranslationStatus.State.GOOGLE -> "G"
    TranslationStatus.State.SAME_LANGUAGE -> "="
    TranslationStatus.State.NONE -> "–"
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
        status = TranslationStatus.statusOf(context, listOf(song.videoId))[song.videoId]
    }
    val words = statusWords()
    val current = status
    val value = current?.let { words.states[it.state] }
    val detail = current?.let { now ->
        buildList {
            now.translation?.let { add(if (it.kept) words.kept else words.cache) }
            if (now.reading != null) add(words.kinds[LyricsTranslation.KIND_READING] + " ✓")
            if (now.meaning != null) add(words.kinds[LyricsTranslation.KIND_MEANING] + " ✓")
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
        ReleaseStatusWindow(release = shown, onDismiss = { KoAlerts.releaseStatus.value = null })
    }
    val storage by KoAlerts.storage.collectAsStateWithLifecycle()
    if (storage) {
        StorageWindow(hazeState = hazeState, onDismiss = { KoAlerts.storage.value = false })
    }
}

/** [song]'s status, looked up again whenever another song is asked for; null while it is read. */
@Composable
private fun rememberSongStatus(song: Song?): TranslationStatus.SongStatus? {
    val context = LocalContext.current
    var status by remember(song?.videoId) { mutableStateOf<TranslationStatus.SongStatus?>(null) }
    LaunchedEffect(song?.videoId) {
        val id = song?.videoId ?: return@LaunchedEffect
        status = TranslationStatus.statusOf(context, listOf(id))[id]
    }
    return status
}

/** The words a song's own page shows above its answers: who sings it, where it stands. */
private class SongPageWords(val nothing: String, val otherNote: String)

@Composable
private fun songPageWords() = SongPageWords(
    nothing = stringResource(R.string.translation_status_nothing),
    otherNote = stringResource(R.string.translation_status_other_note),
)

private fun songHeading(song: Song, status: TranslationStatus.SongStatus?, words: StatusWords, page: SongPageWords): String =
    listOfNotNull(
        song.artist.takeIf { it.isNotBlank() },
        status?.let { words.states[it.state] },
        page.otherNote.takeIf { status?.state == TranslationStatus.State.CLAUDE_OTHER_STYLE },
    ).joinToString("\n")

private fun songLines(status: TranslationStatus.SongStatus?, words: StatusWords): List<Pair<String, String>> =
    status?.answers.orEmpty()
        .sortedWith(compareBy<StoredAnswer> { it.kind }.thenByDescending { it.at })
        .map { answer -> (words.kinds[answer.kind] ?: answer.kind) to words.describe(answer) }

/** Every stored answer of one song, each with who wrote it, in which style, and where it is kept. */
@Composable
private fun SongStatusAlert(hazeState: HazeState, song: Song, onBack: () -> Unit) {
    val current = rememberSongStatus(song)
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
            (if (current != null && lines.isEmpty()) listOf(page.nothing) else lines).forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
) {
    val heading = songHeading(song, status, words, page)
    windowText(if (status == null) loading else heading.ifBlank { song.title })
    if (status == null) return
    val lines = songLines(status, words)
    if (lines.isEmpty()) {
        windowText(page.nothing)
    } else {
        windowCard(rows = lines) { (kind, detail) -> WindowLine(title = kind, detail = detail) }
    }
}

/**
 * A playlist's or an album's songs, each with where it stands, and the
 * missing ones sent ahead at the batch price. A song opens its own page.
 */
@Composable
private fun ReleaseStatusWindow(release: AheadAsk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val songs = remember(release) { release.songs.distinctBy { it.videoId } }
    var statuses by remember(release) { mutableStateOf<Map<String, TranslationStatus.SongStatus>?>(null) }
    LaunchedEffect(release) {
        songs.forEach { TranslationStatus.remember(it.videoId, it.title, it.artist) }
        statuses = TranslationStatus.statusOf(context, songs.map { it.videoId })
    }
    var opened by remember(release) { mutableStateOf<Song?>(null) }
    val openedStatus = rememberSongStatus(opened)
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
    val summary = if (all == null) {
        loading
    } else {
        listOf(
            style,
            stringResource(
                R.string.translation_status_summary,
                counts[TranslationStatus.State.CLAUDE] ?: 0,
                counts[TranslationStatus.State.CLAUDE_OTHER_STYLE] ?: 0,
                counts[TranslationStatus.State.GOOGLE] ?: 0,
                counts[TranslationStatus.State.SAME_LANGUAGE] ?: 0,
                counts[TranslationStatus.State.NONE] ?: 0,
            ),
            stringResource(R.string.translation_status_stores, keptCount, cacheCount),
        ).joinToString("\n")
    }
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
        onBack = { if (opened != null) opened = null else onDismiss() },
        listState = if (inner != null) songState else mainState,
    ) {
        if (inner != null) {
            songStatusItems(inner, openedStatus, words, page, loading)
            return@KoWindow
        }
        windowText(summary, header = summaryHeader, footer = note.takeIf { all != null })
        windowActions(buttons)
        if (all != null) {
            windowCard(rows = songs, header = songsHeader, key = { it.videoId }) { song ->
                val status = all[song.videoId]
                val state = status?.state ?: TranslationStatus.State.NONE
                val detail = buildString {
                    append(words.states[state].orEmpty())
                    status?.translation?.takeIf { status.covered || state == TranslationStatus.State.GOOGLE }?.let {
                        append(" · ").append(if (it.kept) words.kept else words.cache)
                    }
                    if (status?.reading != null) append(" · ").append(words.kinds[LyricsTranslation.KIND_READING]).append(" ✓")
                }
                WindowLine(
                    title = if (song.artist.isBlank()) song.title else song.title + " — " + song.artist,
                    detail = detail,
                    mark = mark(state),
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
    var confirmingClear by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(reload) {
        answers = LyricsTranslation.storedAnswers()
        breakdown = LyricsTranslation.storageBreakdown()
    }
    val openedStatus = rememberSongStatus(opened)
    val keptLimitMb by AppSettings.keptStoreLimitMb.collectAsStateWithLifecycle()
    val words = statusWords()
    val page = songPageWords()
    val all = answers
    val clearedText = stringResource(R.string.translation_storage_cleared)

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
    val summary = if (all == null || parts == null) {
        loading
    } else {
        listOfNotNull(
            stringResource(
                R.string.translation_storage_places,
                megabytes(parts.keptBytes),
                keptLimitMb,
                megabytes(parts.cacheBytes),
                cacheLimit / (1024 * 1024),
            ),
            stringResource(
                R.string.translation_storage_kinds,
                parts.claude.songs, megabytes(parts.claude.bytes),
                parts.google.songs, megabytes(parts.google.bytes),
                parts.reading.songs, megabytes(parts.reading.bytes),
                parts.meaning.songs, megabytes(parts.meaning.bytes),
            ),
            stringResource(R.string.translation_storage_other, parts.other.songs, megabytes(parts.other.bytes))
                .takeIf { parts.other.songs > 0 },
            stringResource(R.string.translation_storage_cache_full).takeIf { parts.cacheBytes * 10 >= cacheLimit * 8 },
            stringResource(R.string.translation_storage_kept_full)
                .takeIf { parts.keptBytes * 10 >= keptLimitMb * 1024L * 1024L * 8 },
        ).joinToString("\n")
    }
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
    val buttons = listOf(
        WindowButton(label = stringResource(R.string.translation_storage_clear_google)) {
            scope.launch {
                val cleared = LyricsTranslation.clearCache(googleOnly = true)
                Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
                reload++
            }
        },
        WindowButton(label = stringResource(R.string.translation_storage_clear_all), destructive = true) {
            confirmingClear = true
        },
    )
    val clearTitle = stringResource(R.string.translation_storage_clear_all)
    val clearDescription = stringResource(
        if (AppSettings.keepClaudeTranslations.value) {
            R.string.translation_storage_clear_all_confirm
        } else {
            R.string.translation_storage_clear_all_confirm_claude
        },
    )
    val mainState = rememberLazyListState()
    val songState = remember(opened?.videoId) { LazyListState() }
    val inner = opened
    KoWindow(
        title = inner?.title ?: stringResource(R.string.translation_storage),
        onBack = {
            when {
                confirmingClear -> confirmingClear = false
                opened != null -> opened = null
                else -> onDismiss()
            }
        },
        listState = if (inner != null) songState else mainState,
        overlay = {
            if (confirmingClear) {
                ConfirmationAlert(
                    hazeState = hazeState,
                    title = clearTitle,
                    description = clearDescription,
                    confirmLabel = clearTitle,
                    onConfirm = {
                        confirmingClear = false
                        scope.launch {
                            val cleared = LyricsTranslation.clearCache(googleOnly = false)
                            Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
                            reload++
                        }
                    },
                    onDismiss = { confirmingClear = false },
                    frosted = false,
                )
            }
        },
    ) {
        if (inner != null) {
            songStatusItems(inner, openedStatus, words, page, loading)
            return@KoWindow
        }
        windowText(summary, header = spaceHeader)
        windowCard(rows = limits, header = limitHeader, footer = limitFooter) { limit ->
            WindowChoice(label = "$limit MB", checked = limit == limitChosen) { AppSettings.setKeptStoreLimitMb(limit) }
        }
        windowActions(buttons, header = manageHeader)
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
