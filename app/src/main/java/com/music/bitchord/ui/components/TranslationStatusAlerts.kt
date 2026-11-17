package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Rows shown at most in a long list; the search narrows the rest. */
private const val MAX_LISTED = 200

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

/** The alerts of the translation status screens, shown by flags in [KoAlerts]. */
@Composable
fun TranslationStatusAlerts(hazeState: HazeState) {
    val song by KoAlerts.songStatus.collectAsStateWithLifecycle()
    song?.let { shown ->
        BackHandler { KoAlerts.songStatus.value = null }
        SongStatusAlert(hazeState = hazeState, song = shown, onBack = { KoAlerts.songStatus.value = null })
    }
    val release by KoAlerts.releaseStatus.collectAsStateWithLifecycle()
    release?.let { shown ->
        BackHandler { KoAlerts.releaseStatus.value = null }
        ReleaseStatusAlert(hazeState = hazeState, release = shown, onDismiss = { KoAlerts.releaseStatus.value = null })
    }
    val storage by KoAlerts.storage.collectAsStateWithLifecycle()
    if (storage) {
        var opened by remember { mutableStateOf<Song?>(null) }
        val inner = opened
        BackHandler { if (inner != null) opened = null else KoAlerts.storage.value = false }
        if (inner != null) {
            SongStatusAlert(hazeState = hazeState, song = inner, onBack = { opened = null })
        } else {
            StorageAlert(hazeState = hazeState, onOpen = { opened = it }, onDismiss = { KoAlerts.storage.value = false })
        }
    }
}

/** Every stored answer of one song, each with who wrote it, in which style, and where it is kept. */
@Composable
private fun SongStatusAlert(hazeState: HazeState, song: Song, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember(song.videoId) { mutableStateOf<TranslationStatus.SongStatus?>(null) }
    LaunchedEffect(song.videoId) {
        status = TranslationStatus.statusOf(context, listOf(song.videoId))[song.videoId]
    }
    val words = statusWords()
    val current = status
    val nothing = stringResource(R.string.translation_status_nothing)
    val otherNote = stringResource(R.string.translation_status_other_note)
    val heading = listOfNotNull(
        song.artist.takeIf { it.isNotBlank() },
        current?.let { words.states[it.state] },
        otherNote.takeIf { current?.state == TranslationStatus.State.CLAUDE_OTHER_STYLE },
    ).joinToString("\n")
    val lines = current?.answers.orEmpty()
        .sortedWith(compareBy<StoredAnswer> { it.kind }.thenByDescending { it.at })
        .map { answer -> (words.kinds[answer.kind] ?: answer.kind) + "\n   " + words.describe(answer) }
    AlertScaffold(hazeState = hazeState, onDismiss = onBack) {
        AlertHeading(title = song.title, message = heading)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            (if (current != null && lines.isEmpty()) listOf(nothing) else lines).forEach { line ->
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

/**
 * A playlist's or an album's songs, each with where it stands, and the
 * missing ones sent ahead at the batch price.
 */
@Composable
private fun ReleaseStatusAlert(hazeState: HazeState, release: AheadAsk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val songs = remember(release) { release.songs.distinctBy { it.videoId } }
    var statuses by remember(release) { mutableStateOf<Map<String, TranslationStatus.SongStatus>?>(null) }
    LaunchedEffect(release) {
        songs.forEach { TranslationStatus.remember(it.videoId, it.title, it.artist) }
        statuses = TranslationStatus.statusOf(context, songs.map { it.videoId })
    }
    val words = statusWords()
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
    val summary = if (all == null) {
        stringResource(R.string.translation_status_loading)
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
            stringResource(R.string.translation_status_note),
        ).joinToString("\n")
    }
    val lines = if (all == null) {
        emptyList()
    } else {
        songs.take(MAX_LISTED).map { song ->
            val status = all[song.videoId]
            val state = status?.state ?: TranslationStatus.State.NONE
            buildString {
                append(mark(state)).append(' ').append(song.title)
                if (song.artist.isNotBlank()) append(" — ").append(song.artist)
                append('\n').append("   ").append(words.states[state].orEmpty())
                status?.translation?.takeIf { status.covered || state == TranslationStatus.State.GOOGLE }?.let {
                    append(" · ").append(if (it.kept) words.kept else words.cache)
                }
                if (status?.reading != null) append(" · ").append(words.kinds[LyricsTranslation.KIND_READING]).append(" ✓")
            }
        }
    }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        AlertHeading(title = release.title, message = summary)
        if (lines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (songs.size > MAX_LISTED) {
                    Text(
                        text = stringResource(R.string.translation_status_more, songs.size - MAX_LISTED),
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
        if (missing.isNotEmpty() && TranslateAhead.available()) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.translation_status_translate_missing, missing.size),
                emphasised = true,
                onClick = {
                    onDismiss()
                    requestTranslateAhead(context, release.title, missing, release.browseId, release.songs)
                },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.close), emphasised = false, onClick = onDismiss)
    }
}

/**
 * Every song with something stored, searchable; what it all takes up, by
 * kind and by place; and the cache's clearing and the kept store's limit.
 */
@Composable
private fun StorageAlert(hazeState: HazeState, onOpen: (Song) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf<List<StoredAnswer>?>(null) }
    var breakdown by remember { mutableStateOf<LyricsTranslation.StorageBreakdown?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var confirmingClear by remember { mutableStateOf(false) }
    var pickingLimit by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        answers = LyricsTranslation.storedAnswers()
        breakdown = LyricsTranslation.storageBreakdown()
    }
    val keptLimitMb by AppSettings.keptStoreLimitMb.collectAsStateWithLifecycle()
    val words = statusWords()
    val all = answers
    val clearedText = stringResource(R.string.translation_storage_cleared)

    if (pickingLimit) {
        val options = listOf(100, 200, 500)
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.translation_storage_limit),
            message = stringResource(R.string.translation_storage_limit_description),
            options = options,
            selected = options.minByOrNull { kotlin.math.abs(it - keptLimitMb) } ?: 100,
            label = { "$it MB" },
            onSelect = {
                AppSettings.setKeptStoreLimitMb(it)
                pickingLimit = false
            },
            onDismiss = { pickingLimit = false },
        )
        return
    }
    if (confirmingClear) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.translation_storage_clear_all),
            description = stringResource(
                if (AppSettings.keepClaudeTranslations.value) {
                    R.string.translation_storage_clear_all_confirm
                } else {
                    R.string.translation_storage_clear_all_confirm_claude
                },
            ),
            confirmLabel = stringResource(R.string.translation_storage_clear_all),
            onConfirm = {
                confirmingClear = false
                scope.launch {
                    val cleared = LyricsTranslation.clearCache(googleOnly = false)
                    Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
            onDismiss = { confirmingClear = false },
        )
        return
    }

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
    val summary = if (all == null || parts == null) {
        stringResource(R.string.translation_status_loading)
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
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        AlertHeading(title = stringResource(R.string.translation_storage), message = summary)
        if (songs.isNotEmpty()) {
            PillTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.translation_storage_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                shown.take(MAX_LISTED).forEach { group ->
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
                    AlertRule()
                    TappableLines(
                        title = if (artist.isBlank()) title else "$title — $artist",
                        detail = detail,
                        onClick = {
                            onOpen(Song(videoId = first.trackId, title = title, artist = artist, thumbnailUrl = null))
                        },
                    )
                }
                if (shown.size > MAX_LISTED) {
                    Text(
                        text = stringResource(R.string.translation_status_more, shown.size - MAX_LISTED),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.translation_storage_clear_google),
            emphasised = false,
            onClick = {
                scope.launch {
                    val cleared = LyricsTranslation.clearCache(googleOnly = true)
                    Toast.makeText(context, clearedText.format(cleared.files, megabytes(cleared.bytes)), Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.translation_storage_clear_all),
            emphasised = false,
            destructive = true,
            onClick = { confirmingClear = true },
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.translation_storage_limit_row, keptLimitMb),
            emphasised = false,
            onClick = { pickingLimit = true },
        )
        AlertRule()
        AlertAction(label = stringResource(R.string.close), emphasised = false, onClick = onDismiss)
    }
}
