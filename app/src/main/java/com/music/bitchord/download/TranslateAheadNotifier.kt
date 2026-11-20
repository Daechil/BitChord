package com.music.bitchord.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.music.bitchord.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The notification bar's view of [TranslateAhead]: one notification while
 * lyrics are being looked for (the one the worker keeps in the foreground),
 * one per job whose batches are out, and the job's result in the same place
 * once it is in.
 *
 * Drawn from the job list alone, whoever changed it, at most once a second
 * and only when what it says has changed. A notification swiped away stays
 * away for that job, but for its result.
 */
object TranslateAheadNotifier {
    private const val CHANNEL_PROGRESS = "translate_ahead_progress"
    private const val CHANNEL_DONE = "translate_ahead_done"
    /** The one channel before progress was shown, which was quiet; replaced by [CHANNEL_DONE]. */
    private const val OLD_CHANNEL = "translate_ahead"
    private const val GROUP = "translate_ahead_jobs"
    private const val PREPARING_ID = 0x7A0001
    private const val SUMMARY_ID = 0x7A0002
    private const val JOB_BASE = 0x7A1000
    private const val JOB_RANGE = 0xFFFF
    private const val SUMMARY_FROM = 3
    private const val PREFS = "bitchord_translate_ahead"
    private const val KEY_DISMISSED = "dismissed"

    const val EXTRA_OPEN_LOG = "com.music.bitchord.OPEN_TRANSLATE_AHEAD_LOG"
    internal const val ACTION_CANCEL = "com.music.bitchord.TRANSLATE_AHEAD_CANCEL"
    internal const val ACTION_CHECK = "com.music.bitchord.TRANSLATE_AHEAD_CHECK"
    internal const val ACTION_DISMISSED = "com.music.bitchord.TRANSLATE_AHEAD_DISMISSED"
    internal const val EXTRA_JOB = "job"

    /** True while the worker holds [PREPARING_ID] as its foreground notification. */
    @Volatile
    var foreground: Boolean = false

    private val lock = Any()
    private var started = false
    private val finished = HashSet<String>()
    /** What each notification last said, so an unchanged one is not posted again. */
    private val lastPosted = HashMap<Int, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
        }
        channels(app)
        reconcile(app)
        scope.launch {
            // A StateFlow hands a slow collector only the latest list, so the pause below drops nothing that matters.
            TranslateAhead.jobs.collect {
                render(app)
                // At most once a second: Android sheds a busier app's updates.
                delay(1_000L)
            }
        }
    }

    fun jobNotificationId(jobId: String): Int = JOB_BASE + (jobId.hashCode() and JOB_RANGE)

    private fun manager(app: Context) = app.getSystemService(NotificationManager::class.java)

    private fun channels(app: Context) {
        val manager = manager(app) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    app.getString(R.string.translate_ahead_channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    app.getString(R.string.translate_ahead_channel_done),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setShowBadge(false) },
            )
            manager.deleteNotificationChannel(OLD_CHANNEL)
        }
    }

    /** Whether progress can be seen at all: notifications allowed, and its channel not turned off. */
    fun progressVisible(context: Context): Boolean {
        val manager = manager(context.applicationContext) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_PROGRESS) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // ---- Dismissed --------------------------------------------------------------

    private fun dismissed(app: Context): Set<String> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    internal fun markDismissed(app: Context, jobId: String) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only the jobs still listed are worth remembering.
        val known = TranslateAhead.jobs.value.map { it.id }.toSet()
        val next = (dismissed(app) + jobId).filter { it in known }.toSet()
        prefs.edit().putStringSet(KEY_DISMISSED, next).apply()
    }

    // ---- Drawing ----------------------------------------------------------------

    /** Removes what an earlier run of the app left up for jobs no longer under way. */
    private fun reconcile(app: Context) {
        val manager = manager(app) ?: return
        val jobs = TranslateAhead.jobs.value
        val live = jobs.filter { it.state == TranslateAhead.State.WAITING || it.state == TranslateAhead.State.CANCELING }
            .map { jobNotificationId(it.id) }.toSet()
        runCatching {
            manager.activeNotifications.forEach { shown ->
                val id = shown.id
                val progress = shown.notification.channelId == CHANNEL_PROGRESS
                when {
                    id == PREPARING_ID && jobs.none { it.state == TranslateAhead.State.PREPARING } -> manager.cancel(id)
                    id == SUMMARY_ID -> manager.cancel(id)
                    progress && id in JOB_BASE..JOB_BASE + JOB_RANGE && id !in live -> manager.cancel(id)
                }
            }
        }
    }

    private fun render(app: Context): Unit = synchronized(lock) {
        val manager = manager(app) ?: return@synchronized
        // Read now, under the lock [done] takes too: a result posted meanwhile is never written over.
        val jobs = TranslateAhead.jobs.value
        val hidden = dismissed(app)

        // The search runs one job at a time, oldest first, as [TranslateAhead.work] picks them.
        val preparing = jobs.filter { it.state == TranslateAhead.State.PREPARING }
        val current = preparing.lastOrNull()
        if (current != null && (current.id !in hidden || foreground)) {
            post(manager, PREPARING_ID, preparingNotification(app, current, preparing.size - 1))
        } else if (!foreground) {
            cancel(manager, PREPARING_ID)
        }

        val out = jobs.filter { it.state == TranslateAhead.State.WAITING || it.state == TranslateAhead.State.CANCELING }
        val grouped = out.count { it.id !in hidden } >= SUMMARY_FROM
        out.forEach { job ->
            if (job.id in finished || job.id in hidden) return@forEach
            post(manager, jobNotificationId(job.id), waitingNotification(app, job, grouped))
        }
        jobs.filter { it.state == TranslateAhead.State.CANCELED }.forEach { cancel(manager, jobNotificationId(it.id)) }
        if (grouped) {
            post(manager, SUMMARY_ID, summaryNotification(app, out.filter { it.id !in hidden }))
        } else {
            cancel(manager, SUMMARY_ID)
        }
    }

    private fun post(manager: NotificationManager, id: Int, built: Pair<String, Notification>) {
        val (says, notification) = built
        if (lastPosted[id] == says) return
        // Without the permission (Android 13+) this shows nothing, and the job goes on regardless.
        if (runCatching { manager.notify(id, notification) }.isSuccess) lastPosted[id] = says
    }

    private fun cancel(manager: NotificationManager, id: Int) {
        if (lastPosted.remove(id) != null) runCatching { manager.cancel(id) }
    }

    /** A job's result, in the place its progress was; nothing for one stopped by hand. */
    fun done(context: Context, job: TranslateAhead.Job): Unit = synchronized(lock) {
        val app = context.applicationContext
        val manager = manager(app) ?: return@synchronized
        finished += job.id
        val id = jobNotificationId(job.id)
        if (job.state == TranslateAhead.State.CANCELED) {
            lastPosted.remove(id)
            runCatching { manager.cancel(id) }
            return@synchronized
        }
        val status = TranslateAhead.TrackStatus
        val asked = job.tracks.count { it.status == status.TRANSLATED || it.status == status.DECLINED || it.status == status.FAILED }
        val title = when (job.state) {
            TranslateAhead.State.DONE -> app.getString(R.string.translate_ahead_done_title, job.title, job.count(status.TRANSLATED), asked)
            TranslateAhead.State.EXPIRED -> app.getString(R.string.translate_ahead_expired_title, job.title)
            else -> app.getString(R.string.translate_ahead_failed_title, job.title)
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(app.getString(R.string.translate_ahead_notification_text))
            .setContentIntent(openLog(app, job.id))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(app, CHANNEL_DONE, R.string.translate_ahead_public_done))
            .setAutoCancel(true)
            .build()
        lastPosted.remove(id)
        runCatching { manager.notify(id, notification) }
    }

    /** The foreground notification the worker holds while lyrics are being looked for. */
    fun foregroundInfo(context: Context): ForegroundInfo {
        val app = context.applicationContext
        channels(app)
        val preparing = TranslateAhead.jobs.value.filter { it.state == TranslateAhead.State.PREPARING }
        val notification = preparing.lastOrNull()?.let { preparingNotification(app, it, preparing.size - 1).second }
            ?: NotificationCompat.Builder(app, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(app.getString(R.string.translate_ahead_public_progress))
                .setOngoing(true)
                .setSilent(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PREPARING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PREPARING_ID, notification)
        }
    }

    // ---- What each says -----------------------------------------------------------

    /** What a progress notification shows, built the same way whatever Android draws it with. */
    private class Content(
        val channel: String,
        val title: String,
        val text: String,
        val max: Int,
        val progress: Int,
        val indeterminate: Boolean,
        /** Equal parts the bar is cut into on Android 16 — one per batch; 0 for one bar. */
        val segments: Int,
        val actions: List<Pair<String, PendingIntent>>,
        val open: PendingIntent?,
        val deleted: PendingIntent?,
        val group: String?,
        /** A few characters for the status bar chip a Live Update gets: "12/40". */
        val chip: String? = null,
    )

    private fun preparingNotification(app: Context, job: TranslateAhead.Job, queued: Int): Pair<String, Notification> {
        val total = job.tracks.size
        val text = buildString {
            append(
                if (job.chunksSent > 0) {
                    app.getString(R.string.translate_ahead_state_preparing_sent, job.prepared, total, job.chunksSent)
                } else {
                    app.getString(R.string.translate_ahead_state_preparing, job.prepared, total)
                },
            )
            if (queued > 0) append(" · ").append(app.getString(R.string.translate_ahead_queued, queued))
        }
        val content = Content(
            channel = CHANNEL_PROGRESS,
            title = app.getString(R.string.translate_ahead_progress_title, job.title),
            text = text,
            max = total,
            progress = job.prepared,
            indeterminate = total == 0,
            segments = 0,
            chip = if (total > 0) "${job.prepared}/$total" else null,
            actions = listOf(app.getString(R.string.translate_ahead_cancel_short) to action(app, ACTION_CANCEL, job.id)),
            open = openLog(app, job.id),
            deleted = action(app, ACTION_DISMISSED, job.id),
            group = null,
        )
        return "p|${job.id}|$text" to build(app, content)
    }

    private fun waitingNotification(app: Context, job: TranslateAhead.Job, grouped: Boolean): Pair<String, Notification> {
        val canceling = job.state == TranslateAhead.State.CANCELING
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val text = if (canceling) {
            app.getString(R.string.translate_ahead_state_canceling)
        } else {
            listOfNotNull(
                app.getString(R.string.translate_ahead_state_waiting_chunks, job.chunksOver, job.chunksSent),
                app.getString(R.string.translate_ahead_wait_hint),
                job.lastPolled.takeIf { it > 0 }?.let { app.getString(R.string.translate_ahead_last_checked, time.format(Date(it))) },
            ).joinToString(" · ")
        }
        val content = Content(
            channel = CHANNEL_PROGRESS,
            title = app.getString(R.string.translate_ahead_progress_title, job.title),
            text = text,
            max = job.chunksSent.coerceAtLeast(1),
            progress = job.chunksOver,
            indeterminate = canceling || job.chunksSent == 0,
            segments = job.chunksSent,
            chip = if (!canceling && job.chunksSent > 0) "${job.chunksOver}/${job.chunksSent}" else null,
            actions = if (canceling) {
                emptyList()
            } else {
                listOf(
                    app.getString(R.string.translate_ahead_check_now) to action(app, ACTION_CHECK, job.id),
                    app.getString(R.string.translate_ahead_cancel_short) to action(app, ACTION_CANCEL, job.id),
                )
            },
            open = openLog(app, job.id),
            deleted = action(app, ACTION_DISMISSED, job.id),
            group = GROUP.takeIf { grouped },
        )
        return "w|${job.id}|$text|$grouped" to build(app, content)
    }

    private fun summaryNotification(app: Context, jobs: List<TranslateAhead.Job>): Pair<String, Notification> {
        val title = app.getString(R.string.translate_ahead_summary, jobs.size)
        val style = NotificationCompat.InboxStyle()
        jobs.forEach { style.addLine("${it.title} · ${it.chunksOver}/${it.chunksSent}") }
        val notification = NotificationCompat.Builder(app, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setSilent(true)
            .setContentIntent(openLog(app, null))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(app, CHANNEL_PROGRESS, R.string.translate_ahead_public_progress))
            .build()
        return "s|${jobs.joinToString { "${it.id}:${it.chunksOver}/${it.chunksSent}" }}" to notification
    }

    /** On the lock screen: that it is happening, not which playlist. */
    private fun publicVersion(app: Context, channel: String, title: Int): Notification =
        NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(app.getString(title))
            .build()

    private fun build(app: Context, content: Content): Notification {
        // Android 16 draws a progress-centric bar, cut into one part per batch.
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching { return buildProgressStyle(app, content) }
        }
        val builder = NotificationCompat.Builder(app, content.channel)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setProgress(content.max, content.progress.coerceIn(0, content.max), content.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(content.open)
            .setDeleteIntent(content.deleted)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(app, content.channel, R.string.translate_ahead_public_progress))
        content.group?.let { builder.setGroup(it) }
        content.actions.forEach { (label, intent) -> builder.addAction(0, label, intent) }
        return builder.build()
    }

    @androidx.annotation.RequiresApi(36)
    private fun buildProgressStyle(app: Context, content: Content): Notification {
        val style = Notification.ProgressStyle()
        if (content.indeterminate) {
            style.setProgressIndeterminate(true)
        } else {
            val parts = if (content.segments > 0) content.segments else 1
            val length = if (content.segments > 0) 1 else content.max.coerceAtLeast(1)
            style.setProgressSegments(List(parts) { Notification.ProgressStyle.Segment(length) })
            style.setProgress(content.progress.coerceIn(0, parts * length))
        }
        val icon = Icon.createWithResource(app, R.drawable.ic_notification_logo)
        val builder = Notification.Builder(app, content.channel)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(app, content.channel, R.string.translate_ahead_public_progress))
        content.open?.let { builder.setContentIntent(it) }
        content.deleted?.let { builder.setDeleteIntent(it) }
        content.group?.let { builder.setGroup(it) }
        content.actions.forEach { (label, intent) ->
            builder.addAction(Notification.Action.Builder(icon, label, intent).build())
        }
        // Asked to be a Live Update — the status bar chip and the top of the
        // lock screen — which the system grants or not, and the listener can
        // turn off per app. Not granted, it is the same notification as before.
        if (com.music.bitchord.data.settings.AppSettings.aheadLiveUpdates.value) {
            builder.setRequestPromotedOngoing(true)
            content.chip?.let { builder.setShortCriticalText(it) }
        }
        return builder.build()
    }

    // ---- Taps -------------------------------------------------------------------

    private fun openLog(app: Context, jobId: String?): PendingIntent? {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            putExtra(EXTRA_OPEN_LOG, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        val code = jobId?.let { jobNotificationId(it) } ?: SUMMARY_ID
        return PendingIntent.getActivity(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** One per job and per action, so a button always acts on the job it is shown with. */
    private fun action(app: Context, action: String, jobId: String): PendingIntent {
        val intent = Intent(app, TranslateAheadNotificationReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_JOB, jobId)
        val code = jobNotificationId(jobId) * 4 + when (action) {
            ACTION_CANCEL -> 1
            ACTION_CHECK -> 2
            else -> 3
        }
        return PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** The progress notification's buttons, and its being swiped away. Not exported: only this app's notifications reach it. */
class TranslateAheadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        TranslateAhead.init(app)
        val jobId = intent.getStringExtra(TranslateAheadNotifier.EXTRA_JOB) ?: return
        when (intent.action) {
            TranslateAheadNotifier.ACTION_CANCEL -> TranslateAhead.cancel(jobId)
            TranslateAheadNotifier.ACTION_CHECK -> {
                val checking = TranslateAhead.checkNow()
                Toast.makeText(
                    app,
                    if (checking) R.string.translate_ahead_checking else R.string.translate_ahead_checked_recently,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            TranslateAheadNotifier.ACTION_DISMISSED -> TranslateAheadNotifier.markDismissed(app, jobId)
        }
    }
}
