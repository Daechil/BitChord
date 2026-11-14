package com.music.bitchord.lockscreen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.music.bitchord.R
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadTranslations
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small bar over other apps with the line being sung and, when the player
 * has already translated the song, its translation — the "floating lyrics" of
 * Korean music apps.
 *
 * Shown only while something plays, the setting is on, "Display over other
 * apps" is granted and the app itself is not in front (where the player
 * already shows the words). Driven by the playback service's lyric tick, so it
 * moves with the same line the notification shows.
 *
 * Drag it anywhere; tap it to open the app; hold it to put it away until the
 * app has been opened again (the setting stays on). Locked, it lets every touch
 * through to the app underneath and cannot be moved — and is drawn at the
 * opacity Android requires for a window touches pass through.
 *
 * The translation is only ever read from what is cached or kept: this bar
 * never asks for one, so it costs nothing.
 */
object FloatingLyrics {
    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_settings"
    private const val KEY_X = "ko_floating_lyrics_x"
    private const val KEY_Y = "ko_floating_lyrics_y"
    /** Android lets touches through an overlay only at this opacity or below. */
    private const val PASS_THROUGH_ALPHA = 0.8f
    private const val TRANSLATION_RECHECK_MS = 15_000L

    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var appInFront = false

    /**
     * Put away by a long press: stays away until the app has been in front
     * again, so leaving the app after that brings it back. Turning it off for
     * good is the setting's job.
     */
    private var dismissed = false
    private val foreground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appInFront = true
            dismissed = false
            removeView()
        }

        override fun onStop(owner: LifecycleOwner) {
            appInFront = false
        }
    }

    private var root: LinearLayout? = null
    private var lineView: TextView? = null
    private var subView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var appliedLock: Boolean? = null

    private var trackId: String? = null
    private var lines: List<LyricLine> = emptyList()
    private var translated: List<LyricLine>? = null
    private var lastTranslationCheck = 0L
    private var translationJob: Job? = null
    private var shownIndex = Int.MIN_VALUE

    /** Called by the playback service as it starts, on the main thread. */
    fun attach(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        appContext = app
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = owned
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(foreground) }
        owned.launch {
            NowPlayingLyrics.current.collectLatest { entry ->
                trackId = entry?.videoId
                lines = entry?.lines.orEmpty()
                translated = null
                lastTranslationCheck = 0L
                shownIndex = Int.MIN_VALUE
                translationJob?.cancel()
                lookUpTranslation(app)
            }
        }
    }

    fun detach(context: Context) {
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(foreground) }
        scope?.cancel()
        scope = null
        translationJob = null
        removeView(context)
    }

    /**
     * The service's lyric tick: every half second while playing, and once as
     * playback stops. Shows, updates or removes the bar.
     */
    fun tick(context: Context, playing: Boolean, positionMs: Long) {
        val wanted = AppSettings.floatingLyrics.value && playing && !appInFront && !dismissed &&
            lines.any { it.text.isNotBlank() } && Settings.canDrawOverlays(context)
        if (!wanted) {
            removeView(context)
            return
        }
        if (root == null && !addView(context)) return
        applyLock(context)

        val index = lines.indexOfLast { it.timeMs <= positionMs }
        if (translated == null && System.currentTimeMillis() - lastTranslationCheck > TRANSLATION_RECHECK_MS) {
            lookUpTranslation(context.applicationContext)
        }
        if (index == shownIndex) return
        shownIndex = index
        val line = lines.getOrNull(index)?.takeIf { !it.isGap && it.text.isNotBlank() }
        lineView?.text = line?.text ?: "♪"
        val sub = line?.let { translated?.getOrNull(index)?.text }?.takeIf { it.isNotBlank() && it != line.text }
        subView?.text = sub.orEmpty()
        subView?.visibility = if (sub == null) View.GONE else View.VISIBLE
    }

    /** The player's translation of this song, from the cache only; asked again now and then. */
    private fun lookUpTranslation(context: Context) {
        val id = trackId ?: return
        val source = lines
        if (source.isEmpty() || translationJob?.isActive == true) return
        lastTranslationCheck = System.currentTimeMillis()
        translationJob = scope?.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    LyricsTranslation.translate(
                        id,
                        source,
                        DownloadTranslations.targetLanguage(context),
                        cacheOnly = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val found = (result as? LyricsTranslation.Result.Translated)?.lines?.takeIf { it.size == source.size }
            if (found != null && trackId == id) {
                translated = found
                shownIndex = Int.MIN_VALUE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addView(context: Context): Boolean {
        val window = context.getSystemService(WindowManager::class.java) ?: return false
        val density = context.resources.displayMetrics.density
        val maxWidth = context.resources.displayMetrics.widthPixels - (32 * density).toInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val line = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            this.maxWidth = maxWidth
        }
        val sub = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            this.maxWidth = maxWidth
            visibility = View.GONE
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xD9121212.toInt())
            }
            addView(line)
            addView(sub)
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, (120 * density).toInt())
        }

        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val holdMs = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var held = false
        // Held still for the long-press time: put the bar away. A drag or a
        // lift before then cancels it, so moving and tapping work as before.
        val putAway = Runnable {
            held = true
            bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dismiss(context)
        }
        bar.setOnTouchListener { view, event ->
            val current = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = current.x
                    startY = current.y
                    dragging = false
                    held = false
                    handler.postDelayed(putAway, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(putAway)
                    }
                    if (dragging && !held) {
                        current.x = startX + dx.toInt()
                        current.y = (startY + dy.toInt()).coerceAtLeast(0)
                        runCatching { window.updateViewLayout(view, current) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(putAway)
                    when {
                        held -> Unit
                        dragging -> prefs.edit().putInt(KEY_X, current.x).putInt(KEY_Y, current.y).apply()
                        else -> openApp(context)
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(putAway)
            }
            true
        }

        return runCatching {
            window.addView(bar, layout)
            root = bar
            lineView = line
            subView = sub
            params = layout
            appliedLock = null
            shownIndex = Int.MIN_VALUE
            true
        }.getOrElse {
            Log.w(TAG, "floating lyrics: could not add the window: ${it.message}")
            false
        }
    }

    /** Locked: touches pass through to the app underneath, at the opacity that requires. */
    private fun applyLock(context: Context) {
        val locked = AppSettings.floatingLyricsLocked.value
        if (locked == appliedLock) return
        val view = root ?: return
        val layout = params ?: return
        val window = context.getSystemService(WindowManager::class.java) ?: return
        layout.flags = if (locked) {
            layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        layout.alpha = if (locked) PASS_THROUGH_ALPHA else 1f
        runCatching { window.updateViewLayout(view, layout) }
        appliedLock = locked
    }

    /** The long press: away until the app has been opened again, and say so. */
    private fun dismiss(context: Context) {
        dismissed = true
        removeView(context)
        runCatching {
            Toast.makeText(context, R.string.floating_lyrics_dismissed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
    }

    private fun removeView(context: Context? = appContext) {
        val view = root ?: return
        val window = context?.getSystemService(WindowManager::class.java)
        runCatching { window?.removeViewImmediate(view) }
        root = null
        lineView = null
        subView = null
        params = null
        appliedLock = null
    }
}
