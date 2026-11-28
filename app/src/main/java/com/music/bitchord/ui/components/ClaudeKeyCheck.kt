package com.music.bitchord.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsTranslation

/**
 * KO: the Claude key, checked where nothing need be sent. Its shape when it is
 * saved; and, once Claude has refused it (a wrong key, or an account with no
 * credit), a word about it once — the translation falls back to Google quietly,
 * and a listener would otherwise never know why.
 */
internal object ClaudeKeyCheck {
    private const val PREFS = "ko_claude_key_notice"
    private const val TOLD = "rejected_told"
    private const val MODEL_TOLD = "model_gone_told"

    /** "sk-ant-" and more, after the same cleaning the request does (spaces, invisible characters). */
    fun looksLikeClaudeKey(key: String): Boolean {
        val cleaned = key.filter { it in '!'..'~' }
        return cleaned.startsWith("sk-ant-") && cleaned.length >= 30
    }

    /**
     * Says once per key that Claude refused it. A mark of the key is kept, never the key:
     * the same key refused again after a restart is not told twice.
     */
    fun onRejected(context: Context) {
        val key = LyricsTranslation.claudeApiKey
        if (key.isBlank()) return
        val mark = key.hashCode().toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(TOLD, null) == mark) return
        prefs.edit().putString(TOLD, mark).apply()
        KoNotice.show(context.getString(R.string.claude_key_rejected_notice))
    }

    /**
     * Says once per app version that the Claude model this build asks for is gone: no key
     * or setting can fix that, only an update with a current model.
     */
    fun onModelGone(context: Context) {
        val mark = com.music.bitchord.BuildConfig.VERSION_NAME + "|" + LyricsTranslation.claudeModel
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(MODEL_TOLD, null) == mark) return
        prefs.edit().putString(MODEL_TOLD, mark).apply()
        KoNotice.show(context.getString(R.string.claude_model_gone_notice))
    }

    /** "Check the key again": a refusal after it is news again. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOLD).apply()
    }
}

/** Watches for Claude refusing the key or the model, for [ClaudeKeyCheck]. Hosted in [KoHealthAlerts]. */
@Composable
internal fun ClaudeKeyRejectionWatch() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        LyricsTranslation.claudeKeyRejected.collect { rejected ->
            if (rejected) ClaudeKeyCheck.onRejected(context)
        }
    }
    LaunchedEffect(Unit) {
        LyricsTranslation.claudeModelGone.collect { gone ->
            if (gone) ClaudeKeyCheck.onModelGone(context)
        }
    }
}
