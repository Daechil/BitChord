package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.LyricsTranslation.StoredAnswer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which songs have a translation stored, read off [LyricsTranslation]'s
 * index of stored answers and set against the style Settings has now — for
 * the song menu, a playlist's "translation status" and Settings' storage page.
 */
object TranslationStatus {
    private const val MAX_LABELS = 2_000
    private val labels = ConcurrentHashMap<String, Pair<String, String>>()
    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        LyricsTranslation.trackLabel = ::labelOf
        // Claude's answers already in the cache go to the kept store as the
        // setting comes on: at first start with it on, and whenever it is
        // switched on again. Each pass leaves nothing half-moved.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            if (AppSettings.keepClaudeTranslations.value) runCatching { LyricsTranslation.moveClaudeToKept() }
        }
        scope.launch {
            AppSettings.keepClaudeTranslations.drop(1).collect { on ->
                if (on) runCatching { LyricsTranslation.moveClaudeToKept() }
            }
        }
    }

    /** A song's name, from wherever the app has seen it: playing, a menu, a download, a job. */
    fun remember(videoId: String, title: String, artist: String) {
        if (videoId.isBlank() || title.isBlank()) return
        if (labels.size > MAX_LABELS) labels.clear()
        labels[videoId] = title to artist
    }

    private fun labelOf(videoId: String): Pair<String, String>? =
        labels[videoId]
            ?: Downloads.labelOf(videoId)
            ?: TranslateAhead.jobs.value.firstNotNullOfOrNull { job ->
                job.tracks.firstOrNull { it.videoId == videoId }?.let { it.title to it.artist }
            }

    /** Where a song stands for the style in force now. */
    enum class State {
        /** Claude's translation in this language and style. */
        CLAUDE,
        /** Claude's translation, but in another language or style: not used as things are. */
        CLAUDE_OTHER_STYLE,
        /** Google's translation only. */
        GOOGLE,
        /** The lyrics are already in this language. */
        SAME_LANGUAGE,
        /** No source had lyrics for it when last looked: nothing to translate. */
        NO_LYRICS,
        NONE,
    }

    class SongStatus(
        val videoId: String,
        val answers: List<StoredAnswer>,
        val state: State,
        /** The translation the state is about, when there is one. */
        val translation: StoredAnswer?,
        val reading: StoredAnswer?,
        val meaning: StoredAnswer?,
    ) {
        /** Has what the player would use now, or needs none. */
        val covered: Boolean get() = state == State.CLAUDE || state == State.SAME_LANGUAGE || state == State.NO_LYRICS
    }

    /** The language translations are asked into now. */
    fun target(context: Context): String = DownloadTranslations.targetLanguage(context)

    private fun sameTarget(stored: String, wanted: String): Boolean {
        if (stored.equals(wanted, ignoreCase = true)) return true
        val a = Locale.forLanguageTag(stored.replace('_', '-'))
        val b = Locale.forLanguageTag(wanted.replace('_', '-'))
        // Chinese is two scripts under one language: only an exact match counts there.
        return a.language == b.language && a.language != "zh" && a.language.isNotEmpty()
    }

    /** Whether [answer] is a Claude translation the player would use with the settings it has now. */
    fun inCurrentStyle(answer: StoredAnswer, target: String): Boolean =
        answer.kind == LyricsTranslation.KIND_TRANSLATION &&
            answer.byClaude &&
            sameTarget(answer.target, target) &&
            // Empty: noted without its style, which can only have been the style it was found in.
            (answer.speech.isNullOrEmpty() || answer.speech == LyricsTranslation.translationSpeech) &&
            (answer.mode.isNullOrEmpty() || answer.mode == LyricsTranslation.translationMode)

    fun statusOf(videoId: String, answers: List<StoredAnswer>, target: String): SongStatus {
        val translations = answers.filter { it.kind == LyricsTranslation.KIND_TRANSLATION }
        val current = translations.filter { inCurrentStyle(it, target) }.maxByOrNull { it.at }
        val google = translations.filter { !it.byClaude && sameTarget(it.target, target) }.maxByOrNull { it.at }
        val other = translations.filter { it.byClaude }.maxByOrNull { it.at }
        val sameLanguage = (current ?: google)?.sameLanguage == true
        val state = when {
            sameLanguage -> State.SAME_LANGUAGE
            current != null -> State.CLAUDE
            other != null && google == null -> State.CLAUDE_OTHER_STYLE
            google != null -> State.GOOGLE
            other != null -> State.CLAUDE_OTHER_STYLE
            else -> State.NONE
        }
        return SongStatus(
            videoId = videoId,
            answers = answers,
            state = state,
            translation = current ?: if (state == State.GOOGLE) google else other ?: google,
            reading = answers.filter { it.kind == LyricsTranslation.KIND_READING && sameTarget(it.target, target) }.maxByOrNull { it.at },
            meaning = answers.filter { it.kind == LyricsTranslation.KIND_MEANING && sameTarget(it.target, target) }.maxByOrNull { it.at },
        )
    }

    /** Each of [videoIds]' status, in the style in force now. */
    suspend fun statusOf(context: Context, videoIds: List<String>): Map<String, SongStatus> {
        val stored = LyricsTranslation.storedFor(videoIds)
        val target = target(context)
        return videoIds.associateWith { id ->
            val status = statusOf(id, stored[id].orEmpty(), target)
            // Nothing stored and no lyrics to be had: not a song waiting for a translation.
            if (status.state == State.NONE && PlayedLyrics.isMissing(id)) {
                SongStatus(id, status.answers, State.NO_LYRICS, null, null, null)
            } else {
                status
            }
        }
    }
}
