package com.music.bitchord.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.ClaudeFailure
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Translates a playlist's or an album's lyrics ahead of time through Claude's
 * Message Batches API: half the price of asking as each song plays, answered
 * within the day rather than within the minute.
 *
 * A job goes through three stretches. Its songs' lyrics are found, the same
 * way the player finds them, and every song that still needs an answer becomes
 * one request ([State.PREPARING]). Those go up as a single batch, which
 * Anthropic works through in its own time ([State.WAITING]). When it has
 * ended, each answer is filed in [LyricsTranslation]'s kept store, where the
 * player finds it as it would one of its own ([State.DONE]).
 *
 * Everything here can be lost and picked up again: a job is on disk from the
 * moment it is asked for, the lyrics search starts over if the app is closed
 * during it, and a batch already sent is only ever asked about, never resent.
 * The jobs double as the log Settings shows.
 */
object TranslateAhead {
    private const val TAG = "TranslateAhead"
    private const val DIRECTORY = "translate_ahead"
    private const val JOBS_FILE = "jobs.json"
    private const val MAX_JOBS = 30
    private const val ONE_OFF_WORK = "translate_ahead_now"
    private const val PERIODIC_WORK = "translate_ahead_poll"
    private const val CHANNEL_ID = "translate_ahead"
    private const val NOTIFICATION_BASE = 0x7A0000
    private const val LYRICS_TIMEOUT_MS = 25_000L

    /** Anthropic keeps a batch's results this long after it was made. */
    private const val RESULTS_KEPT_MS = 29L * 24L * 60L * 60L * 1000L

    object State {
        const val PREPARING = "preparing"
        const val WAITING = "waiting"
        const val CANCELING = "canceling"
        const val DONE = "done"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
        const val CANCELED = "canceled"
    }

    object TrackStatus {
        /** Its lyrics have not been looked for yet. */
        const val PENDING = "pending"
        /** In the batch, not answered yet. */
        const val SENT = "sent"
        const val TRANSLATED = "translated"
        /** Already had a Claude translation in this style. */
        const val ALREADY = "already"
        const val NO_LYRICS = "no_lyrics"
        /** Already in the language (or, for a pronunciation, the script) asked for. */
        const val SAME_LANGUAGE = "same_language"
        const val DECLINED = "declined"
        const val FAILED = "failed"
    }

    @Serializable
    data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long = 0,
        val status: String = TrackStatus.PENDING,
        /** The pronunciation's own outcome, when the job asked for one; null when it did not. */
        val readingStatus: String? = null,
        /** A [ClaudeFailure] name, for a track that failed. */
        val detail: String? = null,
        /** Played since with lyrics the answer did not cover, and translated again then. */
        val lyricsChanged: Boolean = false,
    )

    @Serializable
    data class Job(
        val id: String,
        val title: String,
        val createdAt: Long,
        val model: String,
        val reading: Boolean,
        /** The language and style the answers are in — a translation in another style is not looked for under these. */
        val target: String = "",
        val speech: String = "",
        val mode: String = "",
        val state: String = State.PREPARING,
        val batchId: String? = null,
        val tracks: List<Track> = emptyList(),
        /** How many tracks' lyrics have been looked for, while [State.PREPARING]. */
        val prepared: Int = 0,
        /** Requests in the batch: translations and pronunciations both. */
        val requests: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /** A [ClaudeFailure] name, for a job that failed as a whole. */
        val error: String? = null,
        val finishedAt: Long? = null,
    ) {
        val active: Boolean
            get() = state == State.PREPARING || state == State.WAITING || state == State.CANCELING

        fun count(status: String): Int = tracks.count { it.status == status }

        /** What the batch cost, at half the list price. */
        fun costUsd(): Double {
            val (inPrice, outPrice) = AppSettings.claudePricePerMillion(model + LyricsTranslation.BATCH_MODEL_SUFFIX)
            return inputTokens * inPrice / 1_000_000.0 + outputTokens * outPrice / 1_000_000.0
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jobsSerializer = ListSerializer(Job.serializer())
    private val itemsSerializer = ListSerializer(LyricsTranslation.AheadItem.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val working = Mutex()
    private val lock = Any()
    private var context: Context? = null
    private val loaded = CompletableDeferred<Unit>()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())

    /** Newest first. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        LyricsTranslation.aheadMismatch = ::lyricsChanged
        scope.launch {
            // Merged rather than set: a job may have been started before the file was read.
            val stored = readJobs(app)
            update(save = false) { current -> current + stored.filter { old -> current.none { it.id == old.id } } }
            loaded.complete(Unit)
            if (_jobs.value.any { it.active }) work(app)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (_jobs.value.any { it.active }) scope.launch { work(app) }
            }
        })
    }

    /** Whether [songs] can be asked for at all: Claude set up, and something in the list to ask about. */
    fun available(): Boolean = AppSettings.claudeTranslation.value && LyricsTranslation.claudeKeyUsable

    /** Starts a job for [songs]; its progress and outcome are in [jobs] from here on. */
    fun start(title: String, songs: List<Song>, model: String, reading: Boolean) {
        val app = context ?: return
        val tracks = songs.distinctBy { it.videoId }.map { song ->
            Track(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = song.durationMillis(),
            )
        }
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        val job = Job(
            id = now.toString(36),
            title = title,
            createdAt = now,
            model = model,
            reading = reading,
            target = DownloadTranslations.targetLanguage(app),
            speech = LyricsTranslation.translationSpeech,
            mode = LyricsTranslation.translationMode,
            tracks = tracks,
        )
        update { listOf(job) + it }
        kick(app)
    }

    /**
     * A new job for the tracks of [jobId] that did not get an answer: the ones
     * Claude declined, with Sonnet, when [declined]; otherwise the ones that
     * failed or were never answered, with the same model.
     */
    fun resend(jobId: String, declined: Boolean) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        fun unanswered(status: String?) = if (declined) {
            status == TrackStatus.DECLINED
        } else {
            status == TrackStatus.FAILED || status == TrackStatus.SENT || status == TrackStatus.PENDING
        }
        val songs = job.tracks
            .filter { unanswered(it.status) || unanswered(it.readingStatus) }
            .map { Song(videoId = it.videoId, title = it.title, artist = it.artist, thumbnailUrl = null, albumName = it.album) }
        val durations = job.tracks.associate { it.videoId to it.durationMs }
        if (songs.isEmpty()) return
        val app = context ?: return
        val now = System.currentTimeMillis()
        val next = Job(
            id = now.toString(36),
            title = job.title,
            createdAt = now,
            model = if (declined) LyricsTranslation.CLAUDE_SONNET else job.model,
            reading = job.reading,
            target = DownloadTranslations.targetLanguage(app),
            speech = LyricsTranslation.translationSpeech,
            mode = LyricsTranslation.translationMode,
            tracks = songs.map { song ->
                Track(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    durationMs = durations[song.videoId] ?: 0L,
                )
            },
        )
        update { listOf(next) + it }
        kick(app)
    }

    /** Stops a job: at once while its lyrics are being found, by asking Anthropic once the batch is out. */
    fun cancel(jobId: String) {
        val app = context ?: return
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        when (job.state) {
            State.PREPARING -> edit(jobId) { it.copy(state = State.CANCELED, finishedAt = System.currentTimeMillis()) }
            State.WAITING -> {
                edit(jobId) { it.copy(state = State.CANCELING) }
                scope.launch {
                    job.batchId?.let { LyricsTranslation.cancelAhead(it) }
                    work(app)
                }
            }
        }
    }

    /** Forgets every job that is over; the translations they filed stay. */
    fun clearFinished() {
        val app = context ?: return
        val gone = _jobs.value.filterNot { it.active }
        update { list -> list.filter { it.active } }
        scope.launch { gone.forEach { itemsFile(app, it.id).delete() } }
    }

    // ---- The work -----------------------------------------------------------

    /** Runs now in this process, and queues a worker in case the process does not live to finish. */
    private fun kick(app: Context) {
        scope.launch { work(app) }
        runCatching {
            val request = OneTimeWorkRequestBuilder<TranslateAheadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(ONE_OFF_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Asks about batches hourly for as long as one is out, and not at all otherwise. */
    private fun schedulePolling(app: Context) {
        runCatching {
            val manager = WorkManager.getInstance(app)
            if (_jobs.value.any { it.active }) {
                val request = PeriodicWorkRequestBuilder<TranslateAheadWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                manager.cancelUniqueWork(PERIODIC_WORK)
            }
        }
    }

    /**
     * Moves every job as far as it will go just now. One caller at a time; a
     * second one arriving meanwhile has nothing to add and returns.
     */
    internal suspend fun work(app: Context) {
        loaded.await()
        if (!working.tryLock()) return
        try {
            // Jobs added while this runs are picked up by the next pass.
            while (true) {
                val job = _jobs.value.lastOrNull { it.state == State.PREPARING } ?: break
                try {
                    prepare(app, job)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "could not prepare ${job.id}: $error")
                    finish(app, job.id, State.FAILED, error = ClaudeFailure.ERROR.name)
                }
            }
            _jobs.value.filter { it.state == State.WAITING || it.state == State.CANCELING }.forEach { job ->
                try {
                    collect(app, job)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "could not collect ${job.id}: $error")
                }
            }
        } finally {
            working.unlock()
            schedulePolling(app)
        }
    }

    private fun stillPreparing(jobId: String): Boolean =
        _jobs.value.firstOrNull { it.id == jobId }?.state == State.PREPARING

    private suspend fun prepare(app: Context, job: Job) {
        val requests = ArrayList<Pair<String, JsonObject>>()
        val items = ArrayList<LyricsTranslation.AheadItem>()
        val saved = Downloads.saved.value
        // Started over each time: a search the app was closed during left nothing behind but the track list.
        edit(job.id) { current ->
            current.copy(prepared = 0, tracks = current.tracks.map { it.copy(status = TrackStatus.PENDING, readingStatus = null) })
        }
        job.tracks.forEachIndexed { index, track ->
            if (!stillPreparing(job.id)) return
            val lines = lyricsFor(app, track, saved[track.videoId])
            var status = TrackStatus.NO_LYRICS
            var readingStatus: String? = if (job.reading) TrackStatus.NO_LYRICS else null
            if (lines != null && lines.any { it.text.isNotBlank() }) {
                val translation = LyricsTranslation.aheadRequest("t$index", track.videoId, lines, job.target, job.model, reading = false)
                status = statusOf(translation)
                translation.item?.let { item ->
                    items += item
                    requests += item.customId to (translation.params ?: return@let)
                }
                if (job.reading) {
                    val reading = LyricsTranslation.aheadRequest("r$index", track.videoId, lines, job.target, job.model, reading = true)
                    readingStatus = statusOf(reading)
                    reading.item?.let { item ->
                        items += item
                        requests += item.customId to (reading.params ?: return@let)
                    }
                }
            }
            edit(job.id, save = index % 10 == 9) { current ->
                current.copy(
                    prepared = index + 1,
                    tracks = current.tracks.map {
                        if (it.videoId == track.videoId) it.copy(status = status, readingStatus = readingStatus) else it
                    },
                )
            }
        }
        if (!stillPreparing(job.id)) return
        if (requests.isEmpty()) {
            // Nothing needed asking: every song was translated already, or had no lyrics.
            finish(app, job.id, State.DONE)
            return
        }
        val sent = LyricsTranslation.submitAhead(requests, job.model)
        val batchId = sent.batchId
        if (batchId == null) {
            finish(app, job.id, State.FAILED, error = (sent.failure ?: ClaudeFailure.ERROR).name) { track ->
                track.copy(
                    status = if (track.status == TrackStatus.SENT) TrackStatus.FAILED else track.status,
                    readingStatus = if (track.readingStatus == TrackStatus.SENT) TrackStatus.FAILED else track.readingStatus,
                )
            }
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = itemsFile(app, job.id)
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(itemsSerializer, items))
            }
        }
        edit(job.id) { it.copy(state = State.WAITING, batchId = batchId, requests = requests.size) }
    }

    private fun statusOf(request: LyricsTranslation.AheadRequest): String = when (request.skip) {
        null -> TrackStatus.SENT
        LyricsTranslation.AheadSkip.ALREADY_TRANSLATED -> TrackStatus.ALREADY
        LyricsTranslation.AheadSkip.SAME_LANGUAGE -> TrackStatus.SAME_LANGUAGE
        LyricsTranslation.AheadSkip.NOTHING_TO_TRANSLATE -> TrackStatus.NO_LYRICS
    }

    /**
     * The lyrics the player would show for [track]: the ones written into its
     * file when it is downloaded, otherwise the sources Settings chose, in the
     * order and with the preference set there. The translation is filed under
     * these lines, so the nearer they are to the player's the better.
     */
    private suspend fun lyricsFor(app: Context, track: Track, savedUri: String?): List<LyricLine>? {
        savedUri?.let { uri -> EmbeddedLyrics.forUri(app, uri)?.let { return it } }
        return try {
            withTimeoutOrNull(LYRICS_TIMEOUT_MS) {
                LyricsRepository.lyrics(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    album = track.album,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )?.lines
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
    }

    private suspend fun collect(app: Context, job: Job) {
        val batchId = job.batchId ?: return
        val status = LyricsTranslation.aheadStatus(batchId) ?: return
        val tooOld = System.currentTimeMillis() - job.createdAt > RESULTS_KEPT_MS
        if (status.gone || (status.ended && tooOld && status.resultsUrl == null)) {
            // Its results are no longer kept. What it cost was still billed.
            itemsFile(app, job.id).delete()
            finish(app, job.id, State.EXPIRED) { it.unanswered(ClaudeFailure.TIMEOUT.name) }
            return
        }
        if (!status.ended) return
        val items = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(itemsSerializer, itemsFile(app, job.id).readText()) }.getOrNull()
        }
        if (items == null) {
            // The lines each answer belongs to are gone, so none can be filed.
            finish(app, job.id, State.FAILED, error = ClaudeFailure.ERROR.name) { it.unanswered(ClaudeFailure.ERROR.name) }
            return
        }
        val outcomes = LyricsTranslation.collectAhead(batchId, status.resultsUrl, items) ?: return
        val byId = outcomes.associateBy { it.customId }
        fun result(customId: String, sent: Boolean): Pair<String, String?>? {
            if (!sent) return null
            val outcome = byId[customId] ?: return TrackStatus.FAILED to ClaudeFailure.ERROR.name
            return when (outcome.failure) {
                null -> TrackStatus.TRANSLATED to null
                ClaudeFailure.REFUSAL -> TrackStatus.DECLINED to null
                else -> TrackStatus.FAILED to outcome.failure?.name
            }
        }
        val canceled = job.state == State.CANCELING
        edit(job.id) { current ->
            current.copy(
                state = if (canceled) State.CANCELED else State.DONE,
                finishedAt = System.currentTimeMillis(),
                inputTokens = outcomes.sumOf { it.inputTokens },
                outputTokens = outcomes.sumOf { it.outputTokens },
                tracks = current.tracks.mapIndexed { index, track ->
                    val translation = result("t$index", track.status == TrackStatus.SENT)
                    val reading = result("r$index", track.readingStatus == TrackStatus.SENT)
                    track.copy(
                        status = translation?.first ?: track.status,
                        readingStatus = reading?.first ?: track.readingStatus,
                        detail = translation?.second ?: reading?.second ?: track.detail,
                    )
                },
            )
        }
        itemsFile(app, job.id).delete()
        notifyDone(app, job.id)
    }

    private fun Track.unanswered(detail: String): Track = copy(
        status = if (status == TrackStatus.SENT) TrackStatus.FAILED else status,
        readingStatus = if (readingStatus == TrackStatus.SENT) TrackStatus.FAILED else readingStatus,
        detail = if (status == TrackStatus.SENT || readingStatus == TrackStatus.SENT) detail else this.detail,
    )

    private fun finish(
        app: Context,
        jobId: String,
        state: String,
        error: String? = null,
        tracks: (Track) -> Track = { it },
    ) {
        edit(jobId) { current ->
            current.copy(
                state = state,
                error = error,
                finishedAt = System.currentTimeMillis(),
                tracks = current.tracks.map(tracks),
            )
        }
        notifyDone(app, jobId)
    }

    /** From [LyricsTranslation]: a song translated ahead was played with other lyrics, and asked again. */
    private fun lyricsChanged(trackId: String) {
        if (_jobs.value.none { job -> job.tracks.any { it.videoId == trackId && !it.lyricsChanged && it.status == TrackStatus.TRANSLATED } }) return
        update { list ->
            list.map { job ->
                job.copy(
                    tracks = job.tracks.map {
                        if (it.videoId == trackId && it.status == TrackStatus.TRANSLATED) it.copy(lyricsChanged = true) else it
                    },
                )
            }
        }
    }

    // ---- Storage ------------------------------------------------------------

    private fun directory(app: Context) = File(app.filesDir, DIRECTORY)

    private fun itemsFile(app: Context, jobId: String) = File(directory(app), "items_$jobId.json")

    private suspend fun readJobs(app: Context): List<Job> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(jobsSerializer, File(directory(app), JOBS_FILE).readText()) }
            .getOrDefault(emptyList())
    }

    private fun edit(jobId: String, save: Boolean = true, change: (Job) -> Job) =
        update(save) { list -> list.map { if (it.id == jobId) change(it) else it } }

    private fun update(save: Boolean = true, change: (List<Job>) -> List<Job>) {
        val app = context ?: return
        synchronized(lock) {
            val changed = change(_jobs.value)
            // The oldest finished jobs make room; one still under way is never dropped.
            val kept = if (changed.size > MAX_JOBS) {
                val over = changed.size - MAX_JOBS
                val drop = changed.filterNot { it.active }.takeLast(over).map { it.id }.toSet()
                changed.filterNot { it.id in drop }
            } else {
                changed
            }
            _jobs.value = kept
            if (save) {
                runCatching {
                    val folder = directory(app)
                    folder.mkdirs()
                    val temporary = File(folder, "$JOBS_FILE.tmp")
                    temporary.writeText(json.encodeToString(jobsSerializer, kept))
                    val destination = File(folder, JOBS_FILE)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                }
            }
        }
    }

    // ---- Notification -------------------------------------------------------

    private fun notifyDone(app: Context, jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        // A job stopped by hand needs no announcing.
        if (job.state == State.CANCELED) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.translate_ahead_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val asked = job.tracks.count { it.status == TrackStatus.TRANSLATED || it.status == TrackStatus.DECLINED || it.status == TrackStatus.FAILED }
            val title = when (job.state) {
                State.DONE -> app.getString(
                    R.string.translate_ahead_done_title,
                    job.title,
                    job.count(TrackStatus.TRANSLATED),
                    asked,
                )
                State.EXPIRED -> app.getString(R.string.translate_ahead_expired_title, job.title)
                else -> app.getString(R.string.translate_ahead_failed_title, job.title)
            }
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(app.getString(R.string.translate_ahead_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_BASE + (job.createdAt % 10_000).toInt(), notification)
        }
    }
}

/** The scheduled half of [TranslateAhead]: asks about batches that are out, and files what has come back. */
class TranslateAheadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TranslateAhead.init(applicationContext)
        TranslateAhead.work(applicationContext)
        return Result.success()
    }
}
