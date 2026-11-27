package com.music.bitchord.playback

import androidx.media3.session.MediaController
import com.music.bitchord.data.listentogether.ListenTogether

/**
 * KO: a tap on a row lit as the song playing now.
 *
 * Every list draws that light with `playingRowMatcher`, which also lights
 * another upload of the same song — a single against its album cut — and a
 * tap there used to start the row: the same song again from the top, and the
 * radio it was playing in swapped for the page's list. The light says "this
 * is what you are hearing", so the lists now hand such a tap here instead: it
 * opens the player on what is playing and, if it was paused, plays on from
 * where it was. Only a row's own tap — a page's Play button still starts the
 * page.
 */
object PlayingRowTap {

    /**
     * Every open connection to the player, newest last; see [rememberMediaController].
     *
     * More than one screen connects — the app, and the lock-screen lyrics over
     * it — and each lets go of its own as it closes. Holding one slot, the lock
     * screen closing emptied it while the app's was still open, and every tap
     * after started the row over again.
     */
    private val controllers = java.util.concurrent.CopyOnWriteArrayList<MediaController>()

    internal fun connected(controller: MediaController) {
        controllers.remove(controller)
        controllers.add(controller)
    }

    internal fun released(controller: MediaController) {
        controllers.remove(controller)
    }

    /** The newest connection still open, if any. */
    internal val controller: MediaController? get() = controllers.lastOrNull { it.isConnected }

    /** Opens the player on the song playing now. False when nothing is loaded, and the caller starts the row as before. */
    fun open(): Boolean {
        val player = controller ?: return false
        if (player.currentMediaItem == null) return false
        if (!player.isPlaying) {
            // As the play button does in a locked party: a paused party has nothing to play along to.
            val party = ListenTogether.state.value
            if (!(party.controlsLocked && !party.playback.isPlaying)) player.play()
        }
        PlayerDeepLink.request()
        return true
    }
}
