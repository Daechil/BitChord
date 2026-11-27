package com.music.bitchord.ui.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the lyric panel was last asked to show, kept from one song to the
 * next: a reader who turned the translation on keeps it on as the queue moves,
 * rather than tapping again on every song. Only the reader's own taps change
 * it; a song with nothing stored simply shows its originals and leaves it be.
 * Kept for the life of the app, not written down.
 */
internal object LyricsViewMemory {
    val translation = MutableStateFlow(false)

    /** The pronunciation switch. Brought along to the next song only with the translation, from what is stored. */
    val reading = MutableStateFlow(false)

    /** KO: the furigana switch. Brought along to the next song from what is stored only, never asked for. */
    val furigana = MutableStateFlow(false)

    /**
     * Whether a translation read from storage, with nothing asked for, may go
     * on screen by itself. Not Google's where Claude would answer a tap: shown
     * on its own it would turn the next tap into "off", and the paid-for
     * translation the reader expects would never be asked for.
     */
    fun autoShowAccepts(byClaude: Boolean, claudeFellBack: Boolean, aheadPending: Boolean): Boolean =
        byClaude || aheadPending || !claudeFellBack
}

/** The translation and pronunciation lines' size, as Settings has it: 0 small, 1 normal, 2 large. */
internal fun subLineScale(size: Int): Float = when (size) {
    0 -> 0.85f
    2 -> 1.2f
    else -> 1f
}
