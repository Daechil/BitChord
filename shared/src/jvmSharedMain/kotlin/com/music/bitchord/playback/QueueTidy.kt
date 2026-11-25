package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * The queue's "tidy" button, which the shared queue panel shows when the app
 * has set [open]: given the queue as shown, the playing position, a way to
 * read the queue as it is by the time the listener answers, and the panel's
 * own removal of several entries at once — by identity, in one edit, with
 * one undo — the same one "Remove" on a selection uses.
 */
object QueueTidy {
    @Volatile
    var open: (
        (
            queue: List<Song>,
            currentIndex: Int,
            latest: () -> List<Song>,
            removeEntries: suspend (List<com.music.bitchord.data.model.QueueEntryIdentity>) -> Unit,
        ) -> Unit
    )? = null
}
