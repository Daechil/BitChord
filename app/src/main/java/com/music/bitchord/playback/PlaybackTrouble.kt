package com.music.bitchord.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playback stopped because several songs in a row would not start — the
 * service's limit, which is the connection or a change at YouTube rather than
 * one bad song. Shown once as its own alert, with the log and an update
 * check to hand; cleared as soon as anything plays.
 */
object PlaybackTrouble {
    data class Stopped(
        val songs: Int,
        /** Every failure looked like the connection: say so rather than suggest an update. */
        val network: Boolean,
        val at: Long = System.currentTimeMillis(),
    )

    private val _stopped = MutableStateFlow<Stopped?>(null)
    val stopped: StateFlow<Stopped?> = _stopped.asStateFlow()

    fun stopped(songs: Int, network: Boolean) {
        _stopped.value = Stopped(songs, network)
    }

    /** Something played, or the alert was closed. */
    fun clear() {
        if (_stopped.value != null) _stopped.value = null
    }
}
