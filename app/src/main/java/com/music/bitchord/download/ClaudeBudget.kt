package com.music.bitchord.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.KoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Says so, once each, when this month's Claude spending reaches the share of
 * the monthly limit Settings asks to be told about and when it reaches the
 * limit itself, and when today's limit is reached. Batches still out count
 * toward the month at what they are expected to cost, as the limit does.
 */
object ClaudeBudget {
    private const val CHANNEL = "claude_budget"
    private const val MONTH_ID = 0x7B0001
    private const val DAY_ID = 0x7B0002
    private const val PREFS = "bitchord_claude_budget"
    /** "2026-11|1.0": the month and the limit the level below was reached under. */
    private const val KEY_WARNED_FOR = "warned_for"
    private const val KEY_WARNED_LEVEL = "warned_level"
    private const val KEY_DAY_WARNED = "day_warned"

    private var context: Context? = null
    private val lock = Any()

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            combine(
                AppSettings.claudeUsage,
                AppSettings.claudeToday,
                AppSettings.claudeMonthlyCap,
                AppSettings.claudeCapWarnPercent,
                AppSettings.claudeDailyCap,
            ) { _, _, _, _, _ -> Unit }.collect { runCatching { check(app) } }
        }
        scope.launch { TranslateAhead.jobs.collect { runCatching { check(app) } } }
    }

    /** This month's share of the limit, batches still out included, 0..; null with no limit. */
    fun monthShare(): Double? {
        val cap = AppSettings.claudeMonthlyCap.value
        if (cap <= 0.0) return null
        val pending = runCatching { TranslateAhead.pendingUsd() }.getOrDefault(0.0)
        return (AppSettings.claudeSpentThisMonth() + pending) / cap
    }

    private fun check(app: Context) = synchronized(lock) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cap = AppSettings.claudeMonthlyCap.value
        val share = monthShare()
        if (cap > 0.0 && share != null) {
            val month = java.time.YearMonth.now().toString()
            val warnedFor = "$month|$cap"
            // A new month or another limit: what was said under the old one no longer holds.
            val said = if (prefs.getString(KEY_WARNED_FOR, null) == warnedFor) prefs.getInt(KEY_WARNED_LEVEL, 0) else 0
            val warnAt = AppSettings.claudeCapWarnPercent.value
            val reached = when {
                share >= 1.0 -> 100
                warnAt in 1..99 && share * 100 >= warnAt -> warnAt
                else -> 0
            }
            if (reached > said) {
                notifyMonth(app, reached, cap, share)
            }
            if (reached != said || prefs.getString(KEY_WARNED_FOR, null) != warnedFor) {
                prefs.edit().putString(KEY_WARNED_FOR, warnedFor).putInt(KEY_WARNED_LEVEL, maxOf(reached, said)).apply()
            }
        }
        val dayCap = AppSettings.claudeDailyCap.value
        val today = java.time.LocalDate.now().toString()
        if (dayCap > 0.0 && AppSettings.claudeSpentToday() >= dayCap && prefs.getString(KEY_DAY_WARNED, null) != today) {
            notifyDay(app, dayCap)
            prefs.edit().putString(KEY_DAY_WARNED, today).apply()
        }
    }

    private fun channel(app: Context): NotificationManager? {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return null
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    app.getString(R.string.claude_budget_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setShowBadge(false) },
            )
        }
        return manager
    }

    private fun openApp(app: Context): PendingIntent? {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) ?: return null
        return PendingIntent.getActivity(app, MONTH_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(app: Context, id: Int, title: String, text: String) {
        val manager = channel(app) ?: return
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private fun notifyMonth(app: Context, level: Int, cap: Double, share: Double) {
        val format = KoFormat(app)
        val spent = cap * share
        val title = if (level >= 100) {
            app.getString(R.string.claude_budget_month_reached_title)
        } else {
            app.getString(R.string.claude_budget_month_warn_title, level)
        }
        val text = app.getString(
            if (level >= 100) R.string.claude_budget_month_reached_text else R.string.claude_budget_month_warn_text,
            format.money(spent),
            format.limit(cap),
        )
        post(app, MONTH_ID, title, text)
    }

    private fun notifyDay(app: Context, cap: Double) {
        val format = KoFormat(app)
        post(
            app,
            DAY_ID,
            app.getString(R.string.claude_budget_day_reached_title),
            app.getString(R.string.claude_budget_day_reached_text, format.limit(cap)),
        )
    }
}
