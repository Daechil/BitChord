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

    /**
     * Whether a translation read from storage, with nothing asked for, may go
     * on screen by itself. Not Google's where Claude would answer a tap: shown
     * on its own it would turn the next tap into "off", and the paid-for
     * translation the reader expects would never be asked for.
     */
    fun autoShowAccepts(byClaude: Boolean, claudeFellBack: Boolean, aheadPending: Boolean): Boolean =
        byClaude || aheadPending || !claudeFellBack
}
