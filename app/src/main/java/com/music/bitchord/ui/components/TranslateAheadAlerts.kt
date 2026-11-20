package com.music.bitchord.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.ClaudeDiagnostics
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.TranslateAhead
import com.music.bitchord.ui.player.claudeFailureLabel
import com.music.bitchord.ui.screens.claudeModeLabelRes
import com.music.bitchord.ui.screens.claudeSpeechLabelRes
import com.music.bitchord.ui.screens.compactTokens
import dev.chrisbanes.haze.HazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The alerts of [TranslateAhead], shown by the flags in [KoAlerts]: hosted by
 * [ClaudeAlerts], so the app's main composable holds no state for them.
 */
@Composable
fun TranslateAheadAlerts(hazeState: HazeState) {
    val ask by KoAlerts.aheadAsk.collectAsStateWithLifecycle()
    ask?.let { pending ->
        BackHandler { KoAlerts.aheadAsk.value = null }
        TranslateAheadAskAlert(hazeState = hazeState, ask = pending, onDismiss = { KoAlerts.aheadAsk.value = null })
    }
    val showLog by KoAlerts.aheadLog.collectAsStateWithLifecycle()
    if (showLog) {
        TranslateAheadLogWindow(hazeState = hazeState, onDismiss = { KoAlerts.aheadLog.value = false })
    }
}

/**
 * What "Translate lyrics ahead" was chosen on: the release's name and the
 * tracks to send. [browseId] is set for a playlist that can be watched for
 * new songs, whose every track now is [allSongs].
 */
class AheadAsk(
    val title: String,
    val songs: List<Song>,
    val browseId: String? = null,
    val allSongs: List<Song> = songs,
)

/** What is left of this month's limit, batches still out held back; null when no limit is set. */
private fun remainingCapUsd(): Double? = AppSettings.claudeBudgetLeft()

/**
 * The menu's "Translate lyrics ahead": the dialog, or — once "don't show this
 * again" has been ticked — the batch itself, with the choices made last time.
 */
fun requestTranslateAhead(
    context: Context,
    title: String,
    songs: List<Song>,
    browseId: String? = null,
    allSongs: List<Song> = songs,
) {
    if (songs.isEmpty()) return
    if (AppSettings.translateAheadIntro.value) {
        KoAlerts.aheadAsk.value = AheadAsk(title, songs, browseId, allSongs)
        return
    }
    val model = AppSettings.translateAheadModel.value
    val reading = AppSettings.translateAheadReading.value
    // The screen that asked may be gone by the time the statuses are read.
    val context = context.applicationContext
    // A second tap while the first is still looking at what is stored: one request.
    if (requesting) return
    if (TranslateAhead.isDuplicate(songs.map { it.videoId }, model, reading)) {
        Toast.makeText(context, context.getString(R.string.translate_ahead_duplicate), Toast.LENGTH_LONG).show()
        return
    }
    requesting = true
    requestScope.launch {
        try {
            sendFromMenu(context, title, songs, model, reading)
        } finally {
            requesting = false
        }
    }
}

/** A menu request on its way: set from the tap until its job has started or been refused. */
@Volatile
private var requesting = false

private suspend fun sendFromMenu(context: Context, title: String, songs: List<Song>, model: String, reading: Boolean) {
    val ids = songs.map { it.videoId }.distinct()
    // A store that cannot be read is no reason not to send: every song is counted then.
    val statuses = try {
        com.music.bitchord.download.TranslationStatus.statusOf(context, ids)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
    val plan = aheadPlan(statuses, ids, reading)
    if (plan.requests == 0) {
        Toast.makeText(context, context.getString(R.string.translate_ahead_nothing), Toast.LENGTH_LONG).show()
        return
    }
    val estimate = TranslateAhead.estimateUsd(plan.requests, model)
    val remaining = remainingCapUsd()
    if (remaining != null && estimate > remaining) {
        Toast.makeText(
            context,
            KoFormat(context).let { format ->
                context.getString(R.string.translate_ahead_over_cap, format.money(estimate), format.money(remaining))
            },
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val started = TranslateAhead.start(title, songs, model, reading)
    Toast.makeText(
        context,
        context.getString(if (started) R.string.translate_ahead_started else R.string.translate_ahead_duplicate, title),
        Toast.LENGTH_LONG,
    ).show()
}

/** Where a request from a menu runs: it looks at what is stored before sending. */
private val requestScope = kotlinx.coroutines.MainScope()

/**
 * What sending [ids] ahead would really ask for: songs with no lyrics are
 * left out, and a translation (or pronunciation) already stored is not asked
 * again — so the estimate and the limit check count only what goes.
 */
internal class AheadPlan(
    val songs: Int,
    /** Songs with at least one request. */
    val send: Int,
    val noLyrics: Int,
    /** Songs whose every wanted answer is stored already. */
    val already: Int,
    val requests: Int,
)

internal fun aheadPlan(
    statuses: Map<String, com.music.bitchord.download.TranslationStatus.SongStatus>,
    ids: List<String>,
    reading: Boolean,
): AheadPlan {
    var send = 0
    var noLyrics = 0
    var already = 0
    var requests = 0
    for (id in ids) {
        val status = statuses[id]
        if (status?.state == com.music.bitchord.download.TranslationStatus.State.NO_LYRICS) {
            noLyrics++
            continue
        }
        val needsTranslation = status?.covered != true
        val needsReading = reading && status?.reading == null && status?.state != com.music.bitchord.download.TranslationStatus.State.SAME_LANGUAGE
        val asked = (if (needsTranslation) 1 else 0) + (if (needsReading) 1 else 0)
        if (asked == 0) already++ else send++
        requests += asked
    }
    return AheadPlan(ids.size, send, noLyrics, already, requests)
}

/**
 * What a batch is, what it costs with each model, and the two things to
 * decide: which model, and whether the pronunciation comes too.
 */
@Composable
private fun TranslateAheadAskAlert(hazeState: HazeState, ask: AheadAsk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var model by remember { mutableStateOf(AppSettings.translateAheadModel.value) }
    var reading by remember { mutableStateOf(AppSettings.translateAheadReading.value) }
    var dontShow by remember { mutableStateOf(false) }
    var watch by remember { mutableStateOf(TranslateAhead.isWatched(ask.browseId)) }
    val askNotifications = rememberNotificationAsk()
    val ids = remember(ask) { ask.songs.map { it.videoId }.distinct() }
    val count = ids.size
    var statuses by remember(ask) { mutableStateOf<Map<String, com.music.bitchord.download.TranslationStatus.SongStatus>?>(null) }
    LaunchedEffect(ask) {
        // A store that cannot be read: every song is counted, as before the statuses were looked at.
        statuses = try {
            com.music.bitchord.download.TranslationStatus.statusOf(context, ids)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    // Locked from the first tap: a second one is not a second batch to pay for.
    var sending by remember { mutableStateOf(false) }
    // While the statuses are read, every song is counted, as before.
    val plan = statuses?.let { aheadPlan(it, ids, reading) }
    val requests = plan?.requests ?: (count * if (reading) 2 else 1)
    val language = remember {
        val tag = com.music.bitchord.download.DownloadTranslations.targetLanguage(context)
        Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(Locale.getDefault()).ifBlank { tag }
    }
    val style = listOf(
        language,
        stringResource(claudeSpeechLabelRes(LyricsTranslation.translationSpeech)),
        stringResource(claudeModeLabelRes(LyricsTranslation.translationMode)),
    ).joinToString(" · ")
    val haikuUsd = TranslateAhead.estimateUsd(requests, LyricsTranslation.CLAUDE_HAIKU)
    val sonnetUsd = TranslateAhead.estimateUsd(requests, LyricsTranslation.CLAUDE_SONNET)
    val estimate = if (model == LyricsTranslation.CLAUDE_SONNET) sonnetUsd else haikuUsd
    val remaining = remainingCapUsd()
    val overCap = remaining != null && estimate > remaining
    val format = koFormat()
    val chunk by AppSettings.translateAheadChunk.collectAsStateWithLifecycle()
    val usd = format::money
    val message = listOfNotNull(
        stringResource(R.string.translate_ahead_description),
        stringResource(R.string.translate_ahead_scope, count, style),
        if (plan == null) {
            stringResource(R.string.translate_ahead_plan_checking)
        } else if (plan.requests == 0) {
            stringResource(R.string.translate_ahead_nothing)
        } else {
            listOfNotNull(
                stringResource(R.string.translate_ahead_plan, count, plan.send),
                stringResource(R.string.translate_ahead_plan_already, plan.already).takeIf { plan.already > 0 },
                stringResource(R.string.translate_ahead_plan_no_lyrics, plan.noLyrics).takeIf { plan.noLyrics > 0 },
            ).joinToString(" ")
        },
        stringResource(R.string.translate_ahead_scope_chunks, chunk, (count + chunk - 1) / chunk.coerceAtLeast(1))
            .takeIf { count > chunk },
        stringResource(R.string.translate_ahead_over_cap, usd(estimate), usd(remaining ?: 0.0)).takeIf { overCap },
    ).joinToString("\n\n")
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        AlertHeading(title = stringResource(R.string.translate_ahead), message = message)
        AlertRule()
        AheadOptionRow(
            label = stringResource(R.string.claude_model_haiku),
            detail = stringResource(R.string.translate_ahead_model_haiku_detail, usd(haikuUsd)),
            checked = model == LyricsTranslation.CLAUDE_HAIKU,
            checkbox = false,
            onClick = { model = LyricsTranslation.CLAUDE_HAIKU },
        )
        AlertRule()
        AheadOptionRow(
            label = stringResource(R.string.claude_model_sonnet),
            detail = stringResource(R.string.translate_ahead_model_sonnet_detail, usd(sonnetUsd)),
            checked = model == LyricsTranslation.CLAUDE_SONNET,
            checkbox = false,
            onClick = { model = LyricsTranslation.CLAUDE_SONNET },
        )
        AlertRule()
        AheadOptionRow(
            label = stringResource(R.string.translate_ahead_reading),
            detail = stringResource(R.string.translate_ahead_reading_detail),
            checked = reading,
            checkbox = true,
            onClick = { reading = !reading },
        )
        if (ask.browseId != null) {
            AlertRule()
            AheadOptionRow(
                label = stringResource(R.string.translate_ahead_watch),
                detail = stringResource(R.string.translate_ahead_watch_detail, AppSettings.aheadWatchHours.value),
                checked = watch,
                checkbox = true,
                onClick = { watch = !watch },
            )
        }
        AlertRule()
        AheadOptionRow(
            label = stringResource(R.string.translate_ahead_dont_show),
            detail = stringResource(R.string.translate_ahead_dont_show_detail),
            checked = dontShow,
            checkbox = true,
            onClick = { dontShow = !dontShow },
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.translate_ahead_send),
            emphasised = true,
            // Nothing to send can still be a playlist to start watching for new songs.
            enabled = !sending && !overCap && (plan?.requests != 0 || (ask.browseId != null && watch != TranslateAhead.isWatched(ask.browseId))),
            onClick = onClick@{
                if (sending) return@onClick
                sending = true
                AppSettings.setTranslateAheadChoice(model, reading)
                if (dontShow) AppSettings.setTranslateAheadIntro(false)
                askNotifications()
                val wanted = plan?.requests != 0
                val duplicate = wanted && TranslateAhead.isDuplicate(ask.songs.map { it.videoId }, model, reading)
                if (wanted && !duplicate) TranslateAhead.start(ask.title, ask.songs, model, reading)
                ask.browseId?.let { id ->
                    if (watch) {
                        TranslateAhead.watch(id, ask.title, ask.allSongs, model, reading)
                    } else if (TranslateAhead.isWatched(id)) {
                        TranslateAhead.unwatch(id)
                    }
                }
                if (duplicate) {
                    Toast.makeText(context, context.getString(R.string.translate_ahead_duplicate, ask.title), Toast.LENGTH_LONG).show()
                } else if (wanted) {
                    Toast.makeText(context, context.getString(R.string.translate_ahead_started, ask.title), Toast.LENGTH_LONG).show()
                }
                onDismiss()
            },
        )
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/** A row that is one of a few choices (a tick at the end when chosen) or a setting of its own (a checkbox in front). */
@Composable
private fun AheadOptionRow(label: String, detail: String?, checked: Boolean, checkbox: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkbox) {
            // Null: the row is the control, as it is for the choices above it.
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        if (!checkbox && checked) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * Asks for the notification permission (Android 13+) when it is missing,
 * so a job's progress and result can be shown; refusing changes nothing else.
 */
@Composable
internal fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    return remember(launcher) {
        {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}

/** "Finding lyrics 12/40", "Waiting for Claude", "Done" — where a job stands. */
@Composable
private fun stateLabel(job: TranslateAhead.Job): String = when (job.state) {
    TranslateAhead.State.PREPARING -> if (job.chunksSent > 0) {
        stringResource(R.string.translate_ahead_state_preparing_sent, job.prepared, job.tracks.size, job.chunksSent)
    } else {
        stringResource(R.string.translate_ahead_state_preparing, job.prepared, job.tracks.size)
    }
    TranslateAhead.State.WAITING -> if (job.chunksSent > 1) {
        stringResource(R.string.translate_ahead_state_waiting_chunks, job.chunksOver, job.chunksSent)
    } else {
        stringResource(R.string.translate_ahead_state_waiting)
    }
    TranslateAhead.State.CANCELING -> stringResource(R.string.translate_ahead_state_canceling)
    TranslateAhead.State.DONE -> stringResource(R.string.translate_ahead_state_done)
    TranslateAhead.State.EXPIRED -> stringResource(R.string.translate_ahead_state_expired)
    TranslateAhead.State.CANCELED -> stringResource(R.string.translate_ahead_state_canceled)
    else -> stringResource(R.string.translate_ahead_state_failed)
}

@Composable
private fun modelLabel(model: String): String = stringResource(
    if (model == LyricsTranslation.CLAUDE_SONNET) R.string.claude_model_sonnet else R.string.claude_model_haiku,
)


/**
 * Every job, newest first — what was asked for, when, and how far it has got
 * — and the watched playlists, in a window of its own; a job opens its page
 * of every song and what became of it.
 */
@Composable
private fun TranslateAheadLogWindow(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val format = koFormat()
    val translatedOf = stringResource(R.string.translate_ahead_job_translated)
    val jobs by TranslateAhead.jobs.collectAsStateWithLifecycle()
    val watches by TranslateAhead.watches.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf<TranslateAhead.Watch?>(null) }
    val job = opened?.let { id -> jobs.firstOrNull { it.id == id } }
    val neverChecked = stringResource(R.string.translate_ahead_watch_never)
    val notificationsShown = remember(jobs) {
        com.music.bitchord.download.TranslateAheadNotifier.progressVisible(context)
    }
    val hint = stringResource(if (jobs.isEmpty()) R.string.translate_ahead_log_empty else R.string.translate_ahead_log_hint)
    val notificationsOff = stringResource(R.string.translate_ahead_notifications_off)
    val notificationsOffDetail = stringResource(R.string.translate_ahead_notifications_off_detail)
    val watchingHeader = stringResource(R.string.translate_ahead_watching, AppSettings.aheadWatchHours.value)
    val jobsHeader = stringResource(R.string.ko_window_jobs, jobs.size)
    val watchLine = stringResource(R.string.translate_ahead_watch_line)
    val haiku = stringResource(R.string.claude_model_haiku)
    val sonnet = stringResource(R.string.claude_model_sonnet)
    val modelOf = { model: String -> if (model == LyricsTranslation.CLAUDE_SONNET) sonnet else haiku }
    // Every job's state line, resolved here: the list below is not composable.
    val states = jobs.associate { it.id to stateLabel(it) }
    val clearLabel = stringResource(R.string.translate_ahead_clear)
    val unwatchTitle = stringResource(R.string.translate_ahead_unwatch)
    val unwatchConfirm = stringResource(R.string.translate_ahead_unwatch_confirm)
    val jobPage = job?.let { jobPageText(it) }
    val mainState = rememberLazyListState()
    val jobState = remember(opened) { androidx.compose.foundation.lazy.LazyListState() }
    KoWindow(
        title = job?.title ?: stringResource(R.string.translate_ahead_log),
        onBack = {
            when {
                stopping != null -> stopping = null
                opened != null -> opened = null
                else -> onDismiss()
            }
        },
        listState = if (job != null) jobState else mainState,
        overlay = {
            stopping?.let { watch ->
                ConfirmationAlert(
                    hazeState = hazeState,
                    title = unwatchTitle,
                    description = unwatchConfirm.format(watch.title),
                    confirmLabel = unwatchTitle,
                    onConfirm = {
                        TranslateAhead.unwatch(watch.browseId)
                        stopping = null
                    },
                    onDismiss = { stopping = null },
                    frosted = false,
                )
            }
        },
    ) {
        if (job != null && jobPage != null) {
            jobItems(job, jobPage, onBack = { opened = null })
            return@KoWindow
        }
        windowText(hint)
        if (!notificationsShown) {
            windowCard(rows = listOf(Unit)) {
                WindowLine(
                    title = notificationsOff,
                    detail = notificationsOffDetail,
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
        }
        if (watches.isNotEmpty()) {
            windowCard(rows = watches, header = watchingHeader, key = { "watch:" + it.browseId }) { watch ->
                WindowLine(
                    title = "👁 " + watch.title,
                    detail = watchLine.format(
                        modelOf(watch.model),
                        watch.sent,
                        if (watch.lastChecked > 0) format.time(watch.lastChecked) else neverChecked,
                    ),
                    onClick = { stopping = watch },
                )
            }
        }
        if (jobs.isNotEmpty()) {
            windowCard(rows = jobs, header = jobsHeader, key = { "job:" + it.id }) { item ->
                val translated = item.count(TranslateAhead.TrackStatus.TRANSLATED)
                // When and with what, then how it went: two short lines rather than one long one.
                val line = buildString {
                    append(format.time(item.createdAt)).append(" · ").append(modelOf(item.model))
                    append('\n').append(states[item.id].orEmpty())
                    if (!item.active) {
                        append(" · ").append(translatedOf.format(translated, item.tracks.size))
                        if (item.inputTokens > 0 || item.outputTokens > 0) append(" · ").append(format.money(item.costUsd()))
                    }
                }
                WindowLine(title = item.title, detail = line, onClick = { opened = item.id })
            }
        }
        if (jobs.any { !it.active }) {
            windowActions(listOf(WindowButton(label = clearLabel, destructive = true) { TranslateAhead.clearFinished() }))
        }
    }
}

/** A job's page, in words: its totals, then a line per song. */
private class JobPageText(
    val summary: String,
    val tracks: List<Pair<String, String>>,
    val marks: List<String>,
    val cancel: String,
    val resendSonnet: String,
    val resendFailed: String,
)

@Composable
private fun jobPageText(job: TranslateAhead.Job): JobPageText {
    val status = TranslateAhead.TrackStatus
    val statusLabels = mapOf(
        status.PENDING to stringResource(R.string.translate_ahead_track_pending),
        status.SENT to stringResource(R.string.translate_ahead_track_sent),
        status.TRANSLATED to stringResource(R.string.translate_ahead_track_translated),
        status.ALREADY to stringResource(R.string.translate_ahead_track_already),
        status.NO_LYRICS to stringResource(R.string.translate_ahead_track_no_lyrics),
        status.LYRICS_ERROR to stringResource(R.string.translate_ahead_track_lyrics_error),
        status.SAME_LANGUAGE to stringResource(R.string.translate_ahead_track_same_language),
        status.DECLINED to stringResource(R.string.translate_ahead_track_declined),
        status.FAILED to stringResource(R.string.translate_ahead_track_failed),
    )
    // Resolved up front: the track loop below is not where composable calls belong.
    val failureLabels = ClaudeFailure.entries.associate { it.name to claudeFailureLabel(it) }
    val changedLabel = stringResource(R.string.translate_ahead_track_changed)
    val readingLabel = stringResource(R.string.translate_ahead_reading_label)
    val language = remember(job.target) {
        Locale.forLanguageTag(job.target.replace('_', '-')).getDisplayName(Locale.getDefault()).ifBlank { job.target }
    }
    val style = listOf(
        language,
        stringResource(claudeSpeechLabelRes(job.speech)),
        stringResource(claudeModeLabelRes(job.mode)),
    ).joinToString(" · ")
    val headLine = modelLabel(job.model) + " · " + stateLabel(job) +
        (job.error?.let { " (" + (failureLabels[it] ?: it) + ")" } ?: "")
    val countsLine = stringResource(
        R.string.translate_ahead_summary_counts,
        job.count(status.TRANSLATED),
        job.count(status.ALREADY),
        job.count(status.NO_LYRICS),
        job.count(status.DECLINED),
        job.count(status.FAILED),
    )
    val format = koFormat()
    val costLine = stringResource(
        R.string.translate_ahead_summary_cost,
        format.tokens(job.inputTokens),
        format.tokens(job.outputTokens),
        format.money(job.costUsd()),
    )
    val expiredLine = stringResource(R.string.translate_ahead_expired_note)
    val lyricsErrors = job.count(status.LYRICS_ERROR)
    val lyricsErrorLine = stringResource(R.string.translate_ahead_summary_lyrics_error, lyricsErrors)
    val summary = listOfNotNull(
        headLine,
        style,
        countsLine,
        lyricsErrorLine.takeIf { lyricsErrors > 0 },
        costLine.takeIf { job.inputTokens > 0 || job.outputTokens > 0 },
        expiredLine.takeIf { job.state == TranslateAhead.State.EXPIRED },
    ).joinToString("\n")
    val tracks = job.tracks.map { track ->
        val title = if (track.artist.isBlank()) track.title else track.title + " — " + track.artist
        val detail = buildString {
            append(statusLabels[track.status] ?: track.status)
            if (track.status == status.FAILED) track.detail?.let { append(" (").append(failureLabels[it] ?: it).append(')') }
            track.readingStatus?.let { reading ->
                append(" · ").append(readingLabel.format(statusLabels[reading] ?: reading))
            }
            if (track.lyricsChanged) append('\n').append(changedLabel)
        }
        title to detail
    }
    val marks = job.tracks.map { track ->
        when (track.status) {
            status.TRANSLATED, status.ALREADY -> "✓"
            status.DECLINED, status.FAILED -> "✗"
            status.LYRICS_ERROR -> "!"
            status.PENDING, status.SENT -> "…"
            else -> "–"
        }
    }
    return JobPageText(
        summary = summary,
        tracks = tracks,
        marks = marks,
        cancel = stringResource(R.string.translate_ahead_cancel),
        resendSonnet = stringResource(R.string.translate_ahead_resend_sonnet),
        resendFailed = stringResource(R.string.translate_ahead_resend_failed),
    )
}

/** One job's page: its totals, what can be done with it, then every song. */
private fun LazyListScope.jobItems(job: TranslateAhead.Job, text: JobPageText, onBack: () -> Unit) {
    val status = TranslateAhead.TrackStatus
    windowText(text.summary)
    val anyDeclined = job.tracks.any { it.status == status.DECLINED || it.readingStatus == status.DECLINED }
    val anyFailed = job.tracks.any {
        it.status == status.FAILED || it.readingStatus == status.FAILED || it.status == status.LYRICS_ERROR
    }
    windowActions(
        listOfNotNull(
            WindowButton(label = text.cancel, destructive = true) { TranslateAhead.cancel(job.id) }
                .takeIf { job.state == TranslateAhead.State.PREPARING || job.state == TranslateAhead.State.WAITING },
            WindowButton(label = text.resendSonnet) {
                TranslateAhead.resend(job.id, declined = true)
                onBack()
            }.takeIf { !job.active && anyDeclined && job.model != LyricsTranslation.CLAUDE_SONNET },
            WindowButton(label = text.resendFailed) {
                TranslateAhead.resend(job.id, declined = false)
                onBack()
            }.takeIf { !job.active && anyFailed },
        ),
    )
    windowCard(rows = text.tracks.indices.toList()) { index ->
        val (title, detail) = text.tracks[index]
        WindowLine(title = title, detail = detail, mark = text.marks[index])
    }
}

@Composable
internal fun AlertHeading(title: String, message: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A row of an alert that opens something: a title, and a quieter line under it. */
@Composable
internal fun TappableLines(title: String, detail: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}
