package com.music.bitchord.download

import android.content.Context
import android.util.Log
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
    private const val WATCH_WORK = "translate_ahead_watch"
    private const val WATCHES_FILE = "watches.json"
    /** A song's whole lyrics lookup, the second try under its original name included. */
    private const val LYRICS_TIMEOUT_MS = 60_000L
    private const val DURATION_TIMEOUT_MS = 10_000L

    /**
     * Songs per batch. A batch goes up as soon as this many songs needing an
     * answer are found, so a long playlist is sent in parts while its lyrics
     * are still being looked for, and a search cut short loses at most one
     * part's worth of looking.
     */
    /** Songs per batch for a job from before the size could be chosen. */
    private const val CHUNK_SONGS = 20

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
        /** The lyrics could not be fetched: the lookup ran out of time or there was no connection. Not the same as none. */
        const val LYRICS_ERROR = "lyrics_error"
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
        /** The [Chunk] its requests went up in; null until they have. */
        val chunk: Int? = null,
    )

    /** One batch of a job: up to [CHUNK_SONGS] songs' requests. */
    @Serializable
    data class Chunk(
        val index: Int,
        val batchId: String? = null,
        /** [State.WAITING], [State.DONE], [State.FAILED] or [State.EXPIRED]. */
        val state: String = State.WAITING,
        val requests: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /** A [ClaudeFailure] name, for a batch that was not taken or could not be read. */
        val error: String? = null,
        /** What it was expected to cost when sent: held against the monthly limit until it is collected. */
        val estimatedUsd: Double = 0.0,
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
        /** The single batch of a job from before jobs were sent in parts; see [migrated]. */
        val batchId: String? = null,
        val chunks: List<Chunk> = emptyList(),
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
        /** When its batches were last asked about, for "last checked" in the notification. */
        val lastPolled: Long = 0,
        /** Songs per batch, as Settings had it when the job was made; 0 for a job from before it could be chosen. */
        val chunkSongs: Int = 0,
    ) {
        /** Songs per batch for this job: fixed when it was made, so a change in Settings leaves it alone. */
        val songsPerChunk: Int get() = if (chunkSongs > 0) chunkSongs else CHUNK_SONGS
        val active: Boolean
            get() = state == State.PREPARING || state == State.WAITING || state == State.CANCELING

        fun count(status: String): Int = tracks.count { it.status == status }

        /** Batches sent, and how many of them are over. */
        val chunksSent: Int get() = chunks.count { it.batchId != null }
        val chunksOver: Int get() = chunks.count { it.batchId != null && it.state != State.WAITING }

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
            _watches.value = readWatches(app)
            scheduleWatching(app, replace = false)
        }
        scope.launch {
            // Merged rather than set: a job may have been started before the file was read.
            val stored = readJobs(app).map(::migrated)
            update(save = false) { current -> current + stored.filter { old -> current.none { it.id == old.id } } }
            loaded.complete(Unit)
            // After the jobs are in, so what it shows matches them from the first draw.
            TranslateAheadNotifier.init(app)
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

    /** A job asked for again within this long — a second tap, say — is the same job. */
    private const val SAME_JOB_WINDOW_MS = 30_000L
    private val startLock = Any()

    /** Unique even for two jobs made in the same millisecond. */
    private fun newJobId(now: Long): String =
        now.toString(36) + "-" + java.util.UUID.randomUUID().toString().take(6)

    /**
     * Whether a job for exactly [videoIds], with [model] and [reading], is
     * still under way or was made a moment ago: sending it again would only
     * pay for the same answers twice.
     */
    fun isDuplicate(videoIds: Collection<String>, model: String, reading: Boolean): Boolean {
        val wanted = videoIds.filterNot { PlayedLyrics.isMissing(it) }.toSet()
        if (wanted.isEmpty()) return false
        val now = System.currentTimeMillis()
        return _jobs.value.any { job ->
            job.model == model && job.reading == reading &&
                (job.active || now - job.createdAt < SAME_JOB_WINDOW_MS) &&
                job.tracks.map { it.videoId }.toSet() == wanted
        }
    }

    /** Starts a job for [songs]; its progress and outcome are in [jobs] from here on. False when it could not start. */
    fun start(title: String, songs: List<Song>, model: String, reading: Boolean): Boolean = synchronized(startLock) {
        startLocked(title, songs, model, reading)
    }

    private fun startLocked(title: String, songs: List<Song>, model: String, reading: Boolean): Boolean {
        val app = context ?: return false
        // The same songs sent again while their job is under way, or a moment
        // after: a second tap, not a second job to pay for.
        if (isDuplicate(songs.map { it.videoId }, model, reading)) return false
        // Songs found to have no lyrics are left out: there is nothing to send for them.
        val tracks = songs.distinctBy { it.videoId }.filterNot { PlayedLyrics.isMissing(it.videoId) }.map { song ->
            Track(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = song.durationMillis(),
            )
        }
        if (tracks.isEmpty()) return false
        val now = System.currentTimeMillis()
        val job = Job(
            id = newJobId(now),
            title = title,
            createdAt = now,
            model = model,
            reading = reading,
            target = DownloadTranslations.targetLanguage(app),
            speech = LyricsTranslation.translationSpeech,
            mode = LyricsTranslation.translationMode,
            tracks = tracks,
            chunkSongs = AppSettings.translateAheadChunk.value,
        )
        update { listOf(job) + it }
        kick(app)
        return true
    }

    /**
     * A new job for the tracks of [jobId] that did not get an answer: the ones
     * Claude declined, with Sonnet, when [declined]; otherwise the ones that
     * failed or were never answered, with the same model.
     */
    fun resend(jobId: String, declined: Boolean) = synchronized(startLock) {
        resendLocked(jobId, declined)
    }

    private fun resendLocked(jobId: String, declined: Boolean) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        fun unanswered(status: String?) = if (declined) {
            status == TrackStatus.DECLINED
        } else {
            status == TrackStatus.FAILED || status == TrackStatus.SENT || status == TrackStatus.PENDING ||
                status == TrackStatus.LYRICS_ERROR
        }
        val songs = job.tracks
            .filter { unanswered(it.status) || unanswered(it.readingStatus) }
            .map { Song(videoId = it.videoId, title = it.title, artist = it.artist, thumbnailUrl = null, albumName = it.album) }
        val durations = job.tracks.associate { it.videoId to it.durationMs }
        if (songs.isEmpty()) return
        val app = context ?: return
        val now = System.currentTimeMillis()
        // The same resend asked twice — a second tap on the button — makes one job.
        if (isDuplicate(songs.map { it.videoId }, if (declined) LyricsTranslation.CLAUDE_SONNET else job.model, job.reading)) return
        val next = Job(
            id = newJobId(now),
            title = job.title,
            createdAt = now,
            model = if (declined) LyricsTranslation.CLAUDE_SONNET else job.model,
            reading = job.reading,
            target = DownloadTranslations.targetLanguage(app),
            speech = LyricsTranslation.translationSpeech,
            mode = LyricsTranslation.translationMode,
            chunkSongs = AppSettings.translateAheadChunk.value,
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
        if (job.state != State.PREPARING && job.state != State.WAITING) return
        val out = job.chunks.filter { it.state == State.WAITING && it.batchId != null }
        if (out.isEmpty()) {
            edit(jobId) { it.copy(state = State.CANCELED, finishedAt = System.currentTimeMillis()) }
            return
        }
        // Batches already sent are asked to stop; what they answered before that is still filed.
        edit(jobId) { it.copy(state = State.CANCELING) }
        scope.launch {
            out.forEach { chunk -> chunk.batchId?.let { LyricsTranslation.cancelAhead(it) } }
            work(app)
        }
    }

    private var lastManualCheck = 0L

    /**
     * The notification's "Check now": asks about every batch still out at
     * once. False when it was asked less than a minute ago, and nothing is sent.
     */
    fun checkNow(): Boolean {
        val app = context ?: return false
        val now = System.currentTimeMillis()
        if (now - lastManualCheck < 60_000L) return false
        lastManualCheck = now
        scope.launch { work(app) }
        return true
    }

    /** Forgets every job that is over; the translations they filed stay. */
    fun clearFinished() {
        val app = context ?: return
        val gone = _jobs.value.filterNot { it.active }
        update { list -> list.filter { it.active } }
        scope.launch {
            gone.forEach { job ->
                itemsFile(app, job.id).delete()
                job.chunks.forEach { itemsFile(app, job.id, it.index).delete() }
            }
        }
    }

    // ---- What batches are expected to cost ---------------------------------

    /** Typical tokens of one song's translation, until this device has figures of its own. */
    private const val TYPICAL_INPUT_TOKENS = 2_500L
    private const val TYPICAL_OUTPUT_TOKENS = 3_000L

    /**
     * What [requests] translation or pronunciation requests would cost in a
     * batch with [model], in dollars: from what this device's own translations
     * have taken where the log has a few, otherwise from a typical song.
     */
    fun estimateUsd(requests: Int, model: String): Double {
        val past = com.music.bitchord.data.lyrics.ClaudeDiagnostics.entries.value.filter {
            it.purpose == com.music.bitchord.data.lyrics.ClaudeDiagnostics.PURPOSE_TRANSLATE &&
                !it.failed && it.inputTokens != null && it.outputTokens != null
        }
        val input = if (past.size >= 3) past.sumOf { it.inputTokens ?: 0L } / past.size else TYPICAL_INPUT_TOKENS
        val output = if (past.size >= 3) past.sumOf { it.outputTokens ?: 0L } / past.size else TYPICAL_OUTPUT_TOKENS
        val (inPrice, outPrice) = AppSettings.claudePricePerMillion(model + LyricsTranslation.BATCH_MODEL_SUFFIX)
        return (input * inPrice / 1_000_000.0 + output * outPrice / 1_000_000.0) * requests
    }

    /**
     * What the batches still out are expected to cost. Their tokens are only
     * counted once collected, so the monthly limit holds this back meanwhile.
     */
    fun pendingUsd(): Double =
        _jobs.value.sumOf { job -> job.chunks.filter { it.state == State.WAITING }.sumOf { it.estimatedUsd } }

    // ---- Playlists watched for new songs ------------------------------------

    /**
     * A playlist translated ahead whose new songs are sent too, as they turn
     * up: looked at every [AppSettings.aheadWatchHours] hours.
     */
    @Serializable
    data class Watch(
        val browseId: String,
        val title: String,
        val model: String,
        val reading: Boolean,
        /** Every song the playlist held when last looked at; only others are sent. */
        val known: List<String> = emptyList(),
        val lastChecked: Long = 0,
        /** Songs sent from it so far. */
        val sent: Int = 0,
    )

    private val watchesSerializer = ListSerializer(Watch.serializer())
    private val _watches = MutableStateFlow<List<Watch>>(emptyList())
    val watches: StateFlow<List<Watch>> = _watches.asStateFlow()
    private val watching = Mutex()

    /** Starts watching [browseId]; the [songs] it holds now count as known. */
    fun watch(browseId: String, title: String, songs: List<Song>, model: String, reading: Boolean) {
        val app = context ?: return
        val known = songs.map { it.videoId }
        val existing = _watches.value.firstOrNull { it.browseId == browseId }
        val next = Watch(
            browseId = browseId,
            title = title,
            model = model,
            reading = reading,
            known = (existing?.known.orEmpty() + known).distinct(),
            lastChecked = System.currentTimeMillis(),
            sent = existing?.sent ?: 0,
        )
        setWatches(app, listOf(next) + _watches.value.filter { it.browseId != browseId })
        scheduleWatching(app, replace = false)
    }

    fun unwatch(browseId: String) {
        val app = context ?: return
        setWatches(app, _watches.value.filter { it.browseId != browseId })
        scheduleWatching(app, replace = false)
    }

    fun isWatched(browseId: String?): Boolean = browseId != null && _watches.value.any { it.browseId == browseId }

    /** Looks at every watched playlist due a look, and sends its new songs ahead. */
    internal suspend fun checkWatches(app: Context) {
        if (!watching.tryLock()) return
        try {
            val hours = AppSettings.aheadWatchHours.value.coerceAtLeast(1)
            // A little early still counts: the worker's own timing drifts.
            val due = System.currentTimeMillis() - hours * 3_600_000L * 9 / 10
            for (watch in _watches.value) {
                if (watch.lastChecked > due) continue
                if (!available()) break
                val browseId = if (watch.browseId.startsWith("VL")) watch.browseId else "VL${watch.browseId}"
                val listing = try {
                    PlaylistSync.readWholePlaylist(
                        first = { com.music.bitchord.data.YtMusicRepository.browseSongs(browseId) },
                        more = { com.music.bitchord.data.YtMusicRepository.moreSongs(it) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "could not read ${watch.title}: $error")
                    continue
                }
                // A playlist that could not be read in full is tried again next time.
                if (!listing.complete) continue
                val known = watch.known.toHashSet()
                val fresh = listing.songs.filter { it.videoId !in known }
                // Songs with no lyrics have nothing to send; the rest are only
                // counted as known once a job has really started for them — with
                // Claude off or its key gone, they are tried again next time
                // rather than passed over for good.
                val sendable = fresh.filterNot { PlayedLyrics.isMissing(it.videoId) }
                fresh.forEach { TranslationStatus.remember(it.videoId, it.title, it.artist) }
                val started = sendable.isEmpty() || (
                    available() &&
                        start(app.getString(R.string.translate_ahead_watch_job, watch.title), sendable, watch.model, watch.reading)
                    )
                val updated = if (started) {
                    watch.copy(
                        known = (watch.known + fresh.map { it.videoId }).distinct(),
                        lastChecked = System.currentTimeMillis(),
                        sent = watch.sent + sendable.size,
                    )
                } else {
                    watch.copy(lastChecked = System.currentTimeMillis())
                }
                setWatches(app, _watches.value.map { if (it.browseId == watch.browseId) updated else it })
            }
        } finally {
            watching.unlock()
        }
    }

    /** Runs the watch every [AppSettings.aheadWatchHours] while anything is watched; [replace] after the interval changed. */
    fun scheduleWatching(app: Context, replace: Boolean) {
        runCatching {
            val manager = WorkManager.getInstance(app)
            if (_watches.value.isEmpty()) {
                manager.cancelUniqueWork(WATCH_WORK)
                return
            }
            val hours = AppSettings.aheadWatchHours.value.coerceAtLeast(1).toLong()
            val request = PeriodicWorkRequestBuilder<TranslateAheadWatchWorker>(hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(
                WATCH_WORK,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    private suspend fun readWatches(app: Context): List<Watch> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(watchesSerializer, File(directory(app), WATCHES_FILE).readText()) }
            .getOrDefault(emptyList())
    }

    private fun setWatches(app: Context, list: List<Watch>) {
        synchronized(lock) {
            _watches.value = list
            runCatching {
                val folder = directory(app)
                folder.mkdirs()
                val temporary = File(folder, "$WATCHES_FILE.tmp")
                temporary.writeText(json.encodeToString(watchesSerializer, list))
                val destination = File(folder, WATCHES_FILE)
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            }
        }
    }

    // ---- The work -----------------------------------------------------------

    /** Runs now in this process, and queues a worker in case the process does not live to finish. */
    private fun kick(app: Context) {
        scope.launch { work(app) }
        runCatching {
            // At once rather than after a delay: the worker is what keeps the
            // lyric search alive, in the foreground with its notification, once
            // the app is left.
            val request = OneTimeWorkRequestBuilder<TranslateAheadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
    internal suspend fun work(app: Context, waitForRunning: Boolean = false) {
        loaded.await()
        // A worker waits its turn, which keeps the process alive while the pass
        // already running finishes; anything else would only repeat that pass.
        if (waitForRunning) working.lock() else if (!working.tryLock()) return
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

    /** Requests built for songs not yet sent: what one [Chunk] is made of. */
    private class Pending {
        val requests = ArrayList<Pair<String, JsonObject>>()
        val items = ArrayList<LyricsTranslation.AheadItem>()
        val videoIds = LinkedHashSet<String>()
    }

    /**
     * Looks for each song's lyrics and sends a batch every [Job.songsPerChunk] songs
     * that need one. Picks up where an earlier pass stopped: songs already
     * looked at keep their outcome, songs already sent stay sent, and only
     * songs whose requests were built but never sent are looked at again.
     */
    private suspend fun prepare(app: Context, job: Job) {
        val saved = Downloads.saved.value
        edit(job.id) { current ->
            current.copy(
                tracks = current.tracks.map { track ->
                    val unsent = track.chunk == null &&
                        (track.status == TrackStatus.SENT || track.readingStatus == TrackStatus.SENT)
                    if (unsent) track.copy(status = TrackStatus.PENDING, readingStatus = null) else track
                },
            )
        }
        val pending = Pending()
        var stopped: ClaudeFailure? = null
        // Every song once; then once more for those whose lyrics could not be
        // fetched — a timeout or a dropped connection is not "no lyrics".
        for (pass in 0..1) {
        for ((index, track) in job.tracks.withIndex()) {
            if (!stillPreparing(job.id)) return
            val current = _jobs.value.firstOrNull { it.id == job.id }?.tracks?.getOrNull(index) ?: continue
            if (current.status != (if (pass == 0) TrackStatus.PENDING else TrackStatus.LYRICS_ERROR)) continue
            val found = lyricsFor(app, track, saved[track.videoId])
            val lines = (found as? LyricsLookup.Found)?.lines
            var status = if (found is LyricsLookup.Failed) TrackStatus.LYRICS_ERROR else TrackStatus.NO_LYRICS
            var readingStatus: String? = if (job.reading) status else null
            if (lines != null && lines.any { it.text.isNotBlank() }) {
                val translation = LyricsTranslation.aheadRequest("t$index", track.videoId, lines, job.target, job.model, reading = false)
                status = statusOf(translation)
                pending.add(translation, track.videoId)
                if (job.reading) {
                    val reading = LyricsTranslation.aheadRequest("r$index", track.videoId, lines, job.target, job.model, reading = true)
                    readingStatus = statusOf(reading)
                    pending.add(reading, track.videoId)
                }
            }
            edit(job.id, save = index % 5 == 4) { now ->
                now.copy(
                    prepared = (now.tracks.count { it.status != TrackStatus.PENDING } + 1).coerceAtMost(now.tracks.size),
                    tracks = now.tracks.mapIndexed { i, it ->
                        if (i == index) it.copy(status = status, readingStatus = readingStatus) else it
                    },
                )
            }
            if (pending.videoIds.size >= job.songsPerChunk) {
                stopped = sendChunk(app, job, pending)
                pending.clear()
                if (stopped != null) break
            }
        }
            if (stopped != null) break
        }
        if (!stillPreparing(job.id)) return
        if (stopped == null && pending.videoIds.isNotEmpty()) {
            stopped = sendChunk(app, job, pending)
        }
        val fatal = stopped
        if (fatal != null) {
            // The key was refused, there is no credit, or the monthly limit is reached:
            // nothing more would be taken, so what is left is marked as not sent.
            edit(job.id) { now ->
                now.copy(
                    error = fatal.name,
                    tracks = now.tracks.map { track ->
                        if (track.status == TrackStatus.PENDING) track.copy(status = TrackStatus.FAILED, detail = fatal.name) else track
                    },
                )
            }
        }
        val after = _jobs.value.firstOrNull { it.id == job.id } ?: return
        if (after.chunks.any { it.state == State.WAITING }) {
            edit(job.id) { it.copy(state = State.WAITING) }
        } else {
            // Nothing out: every song was answered already, had no lyrics, or could not be sent.
            finishJob(app, job.id)
        }
    }

    private fun Pending.add(request: LyricsTranslation.AheadRequest, videoId: String) {
        val item = request.item ?: return
        val params = request.params ?: return
        items += item
        requests += item.customId to params
        videoIds += videoId
    }

    private fun Pending.clear() {
        requests.clear()
        items.clear()
        videoIds.clear()
    }

    /**
     * Sends [pending] as the job's next batch and records it. Returns the
     * failure when nothing more could be sent either — a refused key, no
     * credit, the monthly limit — and null otherwise, a batch that failed for
     * a passing reason included: its songs are marked failed and the rest go on.
     */
    private suspend fun sendChunk(app: Context, job: Job, pending: Pending): ClaudeFailure? {
        val index = (_jobs.value.firstOrNull { it.id == job.id }?.chunks?.maxOfOrNull { it.index } ?: -1) + 1
        val songs = pending.videoIds.toSet()
        val estimate = estimateUsd(pending.requests.size, job.model)
        // Over the monthly limit with this batch on top of what is spent and what is still out:
        // not sent, and nothing after it either.
        val left = AppSettings.claudeBudgetLeft()
        val sent = if (left != null && estimate > left) {
            LyricsTranslation.AheadSubmitted(null, ClaudeFailure.BUDGET, "not sent: over this month's limit")
        } else {
            LyricsTranslation.submitAhead(pending.requests.toList(), job.model)
        }
        val batchId = sent.batchId
        if (batchId == null) {
            val failure = sent.failure ?: ClaudeFailure.ERROR
            edit(job.id) { now ->
                now.copy(
                    chunks = now.chunks + Chunk(index = index, state = State.FAILED, requests = pending.requests.size, error = failure.name),
                    tracks = now.tracks.map { track ->
                        if (track.videoId in songs && track.chunk == null) {
                            track.copy(
                                chunk = index,
                                status = if (track.status == TrackStatus.SENT) TrackStatus.FAILED else track.status,
                                readingStatus = if (track.readingStatus == TrackStatus.SENT) TrackStatus.FAILED else track.readingStatus,
                                detail = failure.name,
                            )
                        } else {
                            track
                        }
                    },
                )
            }
            val fatal = failure == ClaudeFailure.KEY_REJECTED || failure == ClaudeFailure.NO_CREDIT || failure == ClaudeFailure.BUDGET
            return if (fatal) failure else null
        }
        // The lines each answer belongs to, for when it comes back; written before the batch is recorded,
        // so a recorded batch always has them.
        withContext(Dispatchers.IO) {
            runCatching {
                val file = itemsFile(app, job.id, index)
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(itemsSerializer, pending.items.toList()))
            }
        }
        edit(job.id) { now ->
            now.copy(
                chunks = now.chunks + Chunk(
                    index = index,
                    batchId = batchId,
                    requests = pending.requests.size,
                    estimatedUsd = estimate,
                ),
                requests = now.requests + pending.requests.size,
                tracks = now.tracks.map { track ->
                    if (track.videoId in songs && track.chunk == null) track.copy(chunk = index) else track
                },
            )
        }
        // Canceled while this batch was going up: [cancel] could not have known
        // of it, so it is stopped here, and the job collects what it answered.
        val state = _jobs.value.firstOrNull { it.id == job.id }?.state
        if (state == State.CANCELED || state == State.CANCELING) {
            LyricsTranslation.cancelAhead(batchId)
            if (state == State.CANCELED) edit(job.id) { it.copy(state = State.CANCELING, finishedAt = null) }
        }
        return null
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
    /** How a song's lyrics lookup went: found, none to be had, or not fetched at all. */
    private sealed interface LyricsLookup {
        class Found(val lines: List<LyricLine>) : LyricsLookup
        data object Missing : LyricsLookup
        /** Out of time, no connection or an error: worth another try. */
        data object Failed : LyricsLookup
    }

    private suspend fun lyricsFor(app: Context, track: Track, savedUri: String?): LyricsLookup {
        savedUri?.let { uri -> EmbeddedLyrics.forUri(app, uri)?.let { return LyricsLookup.Found(it) } }
        // What the player showed, from whichever source it found or was told to use.
        PlayedLyrics.get(track.videoId)?.let { return LyricsLookup.Found(it) }
        if (PlayedLyrics.isMissing(track.videoId)) return LyricsLookup.Missing
        if (!online(app)) return LyricsLookup.Failed
        return try {
            // A playlist row may come without a length, and sources match a recording by it.
            val durationMs = track.durationMs.takeIf { it > 0 } ?: youTubeDurationMs(track.videoId) ?: 0L
            val lines = withTimeoutOrNull(LYRICS_TIMEOUT_MS) {
                LyricsRepository.lyrics(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = durationMs,
                    album = track.album,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )?.lines.orEmpty()
            }
            when {
                lines == null -> LyricsLookup.Failed
                lines.any { it.text.isNotBlank() } -> LyricsLookup.Found(lines)
                // Every source answered and none had them; but with the connection
                // gone halfway, their silence says nothing.
                !online(app) -> LyricsLookup.Failed
                else -> {
                    PlayedLyrics.markMissing(track.videoId)
                    LyricsLookup.Missing
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LyricsLookup.Failed
        }
    }

    private fun online(app: Context): Boolean {
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The track's length from YouTube Music's own queue for it; null when it cannot be had. */
    private suspend fun youTubeDurationMs(videoId: String): Long? = try {
        withTimeoutOrNull(DURATION_TIMEOUT_MS) {
            val response = com.music.bitchord.data.innertube.Innertube.next(videoId)
            lengthText(response, videoId).durationMillis().takeIf { it > 0 }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** The "4:48" of [videoId]'s row in a `next` response. */
    private fun lengthText(element: kotlinx.serialization.json.JsonElement, videoId: String): String? = when (element) {
        is JsonObject -> {
            val row = element["playlistPanelVideoRenderer"] as? JsonObject
            val own = row?.takeIf { (it["videoId"] as? kotlinx.serialization.json.JsonPrimitive)?.content == videoId }
                ?.get("lengthText")?.let(::plainText)
            own ?: element.values.firstNotNullOfOrNull { lengthText(it, videoId) }
        }
        is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { lengthText(it, videoId) }
        else -> null
    }

    private fun plainText(element: kotlinx.serialization.json.JsonElement): String? {
        val text = element as? JsonObject ?: return null
        (text["simpleText"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { return it }
        val runs = text["runs"] as? kotlinx.serialization.json.JsonArray ?: return null
        return runs.joinToString("") { ((it as? JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
            .takeIf { it.isNotBlank() }
    }

    /** Asks about each of [job]'s batches still out, files what has come back, and closes the job once none is left. */
    private suspend fun collect(app: Context, job: Job) {
        for (chunk in job.chunks) {
            if (chunk.state != State.WAITING) continue
            try {
                collectChunk(app, job, chunk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d(TAG, "could not collect ${job.id}/${chunk.index}: $error")
            }
        }
        edit(job.id, save = false) { it.copy(lastPolled = System.currentTimeMillis()) }
        val now = _jobs.value.firstOrNull { it.id == job.id } ?: return
        // Still being prepared: more batches may yet go up, and [prepare] closes it.
        if (now.state == State.PREPARING) return
        if (now.chunks.none { it.state == State.WAITING }) finishJob(app, job.id)
    }

    private suspend fun collectChunk(app: Context, job: Job, chunk: Chunk) {
        val batchId = chunk.batchId ?: return
        val status = LyricsTranslation.aheadStatus(batchId) ?: return
        val tooOld = System.currentTimeMillis() - job.createdAt > RESULTS_KEPT_MS
        fun close(state: String, error: String? = null, track: (Track) -> Track) = edit(job.id) { now ->
            now.copy(
                chunks = now.chunks.map { if (it.index == chunk.index) it.copy(state = state, error = error) else it },
                tracks = now.tracks.map { if (it.chunk == chunk.index) track(it) else it },
            )
        }
        if (status.gone || (status.ended && tooOld && status.resultsUrl == null)) {
            // Its results are no longer kept. What it cost was still billed.
            itemsFile(app, job.id, chunk.index).delete()
            close(State.EXPIRED) { it.unanswered(ClaudeFailure.TIMEOUT.name) }
            return
        }
        if (!status.ended) return
        val items = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(itemsSerializer, itemsFile(app, job.id, chunk.index).readText()) }.getOrNull()
        }
        if (items == null) {
            // The lines each answer belongs to are gone, so none can be filed.
            close(State.FAILED, ClaudeFailure.ERROR.name) { it.unanswered(ClaudeFailure.ERROR.name) }
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
        val input = outcomes.sumOf { it.inputTokens }
        val output = outcomes.sumOf { it.outputTokens }
        edit(job.id) { now ->
            now.copy(
                inputTokens = now.inputTokens + input,
                outputTokens = now.outputTokens + output,
                chunks = now.chunks.map {
                    if (it.index == chunk.index) it.copy(state = State.DONE, inputTokens = input, outputTokens = output) else it
                },
                tracks = now.tracks.mapIndexed { index, track ->
                    if (track.chunk != chunk.index) return@mapIndexed track
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
        itemsFile(app, job.id, chunk.index).delete()
    }

    /**
     * Closes a job none of whose batches is out any more: canceled when it was
     * being canceled, done when any batch came back, otherwise expired or
     * failed — and says so, once.
     */
    private fun finishJob(app: Context, jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        val state = when {
            job.state == State.CANCELING -> State.CANCELED
            job.chunks.isEmpty() && job.error == null -> State.DONE
            job.chunks.any { it.state == State.DONE } -> State.DONE
            job.chunks.any { it.state == State.EXPIRED } -> State.EXPIRED
            else -> State.FAILED
        }
        val error = job.error ?: job.chunks.firstNotNullOfOrNull { it.error }?.takeIf { state == State.FAILED }
        finish(app, jobId, state, error = error)
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

    private fun itemsFile(app: Context, jobId: String, chunk: Int) = File(directory(app), "items_${jobId}_$chunk.json")

    /**
     * A job saved before jobs went up in parts, as one that did: its one
     * batch becomes chunk 0, and its items file is renamed to match.
     */
    private fun migrated(job: Job): Job {
        if (job.chunks.isNotEmpty()) return job
        val app = context ?: return job
        val batchId = job.batchId ?: return job
        runCatching {
            val old = itemsFile(app, job.id)
            if (old.isFile) old.renameTo(itemsFile(app, job.id, 0))
        }
        val waiting = job.state == State.WAITING || job.state == State.CANCELING
        return job.copy(
            batchId = null,
            chunks = listOf(
                Chunk(
                    index = 0,
                    batchId = batchId,
                    state = if (waiting) State.WAITING else State.DONE,
                    requests = job.requests,
                    inputTokens = job.inputTokens,
                    outputTokens = job.outputTokens,
                ),
            ),
            tracks = job.tracks.map {
                val sent = it.status == TrackStatus.SENT || it.readingStatus == TrackStatus.SENT ||
                    it.status == TrackStatus.TRANSLATED || it.status == TrackStatus.DECLINED || it.status == TrackStatus.FAILED
                if (sent) it.copy(chunk = 0) else it
            },
        )
    }

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
        TranslateAheadNotifier.done(app, job)
    }
}

/** The scheduled half of [TranslateAhead]: asks about batches that are out, and files what has come back. */
class TranslateAheadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TranslateAhead.init(applicationContext)
        // While lyrics are being looked for, in the foreground: the search goes
        // on with the app left, and the bar shows it. Refused when this starts
        // with the app in the background (Android 12+); then it runs as before.
        if (TranslateAhead.jobs.value.any { it.state == TranslateAhead.State.PREPARING }) {
            try {
                setForeground(getForegroundInfo())
                TranslateAheadNotifier.foreground = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                Log.d("TranslateAhead", "not in the foreground: $refused")
            }
        }
        try {
            TranslateAhead.work(applicationContext, waitForRunning = true)
        } finally {
            TranslateAheadNotifier.foreground = false
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo =
        TranslateAheadNotifier.foregroundInfo(applicationContext)
}

/** The watch of [TranslateAhead]: looks at watched playlists for new songs. */
class TranslateAheadWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TranslateAhead.init(applicationContext)
        TranslateAhead.checkWatches(applicationContext)
        // Whatever the check started is seen through here, while the worker keeps the process up.
        TranslateAhead.work(applicationContext, waitForRunning = true)
        return Result.success()
    }
}
