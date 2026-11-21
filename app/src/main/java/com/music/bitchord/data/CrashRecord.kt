package com.music.bitchord.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.music.bitchord.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What is kept on the phone when the app went down the wrong way: its own
 * last error, written as it falls, and what Android says ended the process
 * last time — a crash, "not responding", or being closed for memory while
 * playing. The log lives in memory only, so without this a crash takes the
 * very lines that would explain it.
 *
 * Shown once on the next launch, and added to the top of Copy Log for a
 * week. Kept outside what is backed up. Nothing here holds lyrics or the
 * Claude key: an error's message is cut short and cleaned of anything that
 * looks like a key, and of any text that is not plain ASCII (a lyric line in
 * an exception would be Japanese or Korean as often as not).
 */
object CrashRecord {
    private const val DIRECTORY = "crash"
    private const val LAST_CRASH = "last_crash.txt"
    private const val SEEN_UP_TO = "exit_seen_up_to"
    private const val MAX_REPORT_CHARS = 16_000
    private const val LOG_TAIL_CHARS = 8_000
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** A report waiting to be shown once. */
    data class Report(val at: Long, val reason: Reason, val text: String)

    enum class Reason { CRASH, NATIVE, ANR, LOW_MEMORY, OTHER }

    private val _pending = MutableStateFlow<Report?>(null)
    val pending: StateFlow<Report?> = _pending.asStateFlow()

    /** The last week's reports, newest first, as one line each for Copy Log. */
    @Volatile
    private var recentLines: List<String> = emptyList()

    private var dir: File? = null

    /** First thing at start, so a crash anywhere after is recorded. */
    fun install(context: Context) {
        val folder = File(context.noBackupFilesDir, DIRECTORY)
        dir = folder
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(folder, thread, error) }
            if (previous != null) previous.uncaughtException(thread, error) else kotlin.system.exitProcess(10)
        }
    }

    /** Written as the app goes down: no coroutines, nothing that can wait. */
    private fun writeCrash(folder: File, thread: Thread, error: Throwable) {
        folder.mkdirs()
        val text = buildString {
            appendLine("time: ${stamp(System.currentTimeMillis())}")
            appendLine("build: ${BuildConfig.VERSION_NAME}")
            appendLine("thread: ${thread.name}")
            if (error is OutOfMemoryError) {
                appendLine("OutOfMemoryError")
            } else {
                appendLine(describe(error))
                runCatching { TrackLog.tail(LOG_TAIL_CHARS) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine("last log lines:")
                    appendLine(it)
                }
            }
        }.take(MAX_REPORT_CHARS)
        val target = File(folder, LAST_CRASH)
        val temporary = File(folder, "$LAST_CRASH.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    /** The error and its causes: class, cleaned message, and where. */
    internal fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) append("caused by: ")
            append(current.javaClass.name)
            scrub(current)?.let { append(": ").append(it) }
            appendLine()
            current.stackTrace.take(25).forEach { appendLine("    at $it") }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }

    private fun scrub(error: Throwable): String? {
        val name = error.javaClass.name
        // Their messages quote the input they could not read.
        if (name.contains("Serialization") || name.contains("JSONException") || name.contains("JsonDecoding")) return null
        return scrub(error.message)
    }

    /** A message fit to keep: short, no key, no lyric text. */
    internal fun scrub(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.any { it.code > 0x7E || (it.code < 0x20 && it != '\n' && it != '\t') }) return "<withheld>"
        return text
            .replace(Regex("sk-ant-[A-Za-z0-9_\\-]+"), "<redacted>")
            .replace(Regex("[A-Za-z0-9_\\-]{32,}"), "<redacted>")
            .take(160)
    }

    /** On the next launch, off the main thread: what ended the last run, if it went wrong. */
    fun collect(context: Context) {
        val folder = dir ?: File(context.noBackupFilesDir, DIRECTORY).also { dir = it }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { collectNow(context, folder) }
        }
    }

    private fun collectNow(context: Context, folder: File) {
        folder.mkdirs()
        val seenFile = File(folder, SEEN_UP_TO)
        val firstRun = !seenFile.isFile
        val seenUpTo = seenFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val reports = mutableListOf<Report>()

        File(folder, LAST_CRASH).takeIf { it.isFile }?.let { file ->
            val at = file.lastModified()
            if (at > seenUpTo) reports += Report(at, Reason.CRASH, file.readText())
        }
        var newest = seenUpTo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrDefault(emptyList())
            exits.forEach { exit ->
                newest = maxOf(newest, exit.timestamp)
                if (exit.timestamp <= seenUpTo) return@forEach
                val reason = reasonOf(exit.reason, exit.importance) ?: return@forEach
                // The app's own crash is already above, with more in it.
                if (reason == Reason.CRASH && reports.any { it.reason == Reason.CRASH && kotlin.math.abs(it.at - exit.timestamp) < 60_000 }) return@forEach
                reports += Report(exit.timestamp, reason, exitText(exit, reason))
            }
        }
        newest = maxOf(newest, reports.maxOfOrNull { it.at } ?: 0L, System.currentTimeMillis())
        seenFile.writeText(newest.toString())

        val history = File(folder, "history.txt")
        val kept = (reports.map { "${stamp(it.at)} ${it.reason.name.lowercase()}" } +
            (history.takeIf { it.isFile }?.readLines().orEmpty()))
            .filter { line -> parseStamp(line.substringBefore(" ", ""), line)?.let { System.currentTimeMillis() - it < WEEK_MS } ?: false }
            .distinct()
            .take(10)
        history.writeText(kept.joinToString("\n"))
        recentLines = kept

        // The first launch of this build looks back at runs from before it
        // was here: those set the starting point, not a card.
        if (firstRun) return
        reports.maxByOrNull { it.at }?.let { latest ->
            val others = reports.filter { it !== latest }.joinToString("\n\n") { it.text }
            _pending.value = latest.copy(text = latest.text + if (others.isNotEmpty()) "\n\n$others" else "")
        }
    }

    /** Which endings are worth a card. Closing the app, an update or Android tidying a cached app are not. */
    internal fun reasonOf(reason: Int, importance: Int): Reason? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> Reason.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> Reason.NATIVE
        ApplicationExitInfo.REASON_ANR -> Reason.ANR
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Reason.OTHER
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) Reason.LOW_MEMORY else null
        else -> null
    }

    private fun exitText(exit: ApplicationExitInfo, reason: Reason): String = buildString {
        appendLine("time: ${stamp(exit.timestamp)}")
        appendLine("ended by Android: ${reason.name.lowercase()} (reason ${exit.reason}, importance ${exit.importance})")
        scrub(exit.description)?.let { appendLine("description: $it") }
        if (reason == Reason.ANR) {
            // The main thread's stack from the system's trace, a few dozen lines.
            runCatching {
                exit.traceInputStream?.bufferedReader()?.use { reader ->
                    val lines = reader.readLines()
                    val start = lines.indexOfFirst { it.startsWith("\"main\"") }
                    if (start >= 0) {
                        appendLine("main thread:")
                        lines.drop(start).take(40).forEach { line -> appendLine(scrub(line) ?: "") }
                    }
                }
            }
        }
    }

    /** One line each for the top of Copy Log: the last week's bad endings. */
    fun headerLines(): List<String> =
        recentLines.takeIf { it.isNotEmpty() }?.let { listOf("abnormal exits (7 days): " + it.joinToString(" · ")) }.orEmpty()

    /** The card was seen. */
    fun dismiss() {
        _pending.value = null
    }

    private fun stamp(at: Long): String = SimpleDateFormat("MM-dd_HH:mm:ss", Locale.US).format(Date(at))

    /** Back from [stamp], this year assumed; null if unreadable. */
    private fun parseStamp(stamp: String, line: String): Long? = runCatching {
        val format = SimpleDateFormat("yyyy MM-dd_HH:mm:ss", Locale.US)
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        var at = format.parse("$year $stamp")!!.time
        if (at > System.currentTimeMillis() + 60_000) at = format.parse("${year - 1} $stamp")!!.time
        at
    }.getOrNull()
}
