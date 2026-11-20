package com.music.bitchord.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where the player's seek bar gets a song's waveform from: [loader], set by
 * the app, answers with the song's loudness from start to end as values
 * 0..1, or null when it has none. The player module draws; the app decides
 * what is known and when it changes ([revision] goes up when a song gets one).
 */
object PlayerWaveforms {
    /** The setting: drawn only while true. */
    val enabled = MutableStateFlow(false)

    /** Bumped whenever a song's waveform is added or removed. */
    val revision = MutableStateFlow(0)

    @Volatile
    var loader: (suspend (String) -> FloatArray?)? = null

    suspend fun load(videoId: String): FloatArray? = try {
        loader?.invoke(videoId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
