package com.music.bitchord.playback.smart

import android.media.MediaDataSource
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A whole song's loudness, for drawing its seek bar: the RMS of each quarter
 * second, averaged down to [POINTS] values 0..1. Streamed as it decodes, so
 * the PCM is never held.
 */
internal object SeekWaveform {
    const val POINTS = 120
    private const val SLICE_SECONDS = 0.25
    private const val MAX_SECONDS = 4.0 * 60.0 * 60.0

    fun levels(source: MediaDataSource, abort: () -> Boolean = { false }): FloatArray? {
        val slices = ArrayList<Float>(1024)
        var sum = 0.0
        var count = 0
        var sliceEnd = SLICE_SECONDS
        AudioDecoder.streamRegion(source, 0.0, MAX_SECONDS, abort) { samples, at, rate ->
            if (rate <= 0) return@streamRegion
            var index = 0
            while (index < samples.size) {
                val time = at + index.toDouble() / rate
                if (time >= sliceEnd) {
                    slices.add(if (count > 0) sqrt(sum / count).toFloat() else 0f)
                    sum = 0.0
                    count = 0
                    sliceEnd += SLICE_SECONDS
                    continue
                }
                // The samples up to the slice's end, in one run.
                val until = minOf(samples.size, ((sliceEnd - at) * rate).toInt().coerceAtLeast(index + 1))
                for (i in index until until) {
                    val x = samples[i]
                    sum += x * x
                }
                count += until - index
                index = until
            }
        } ?: return null
        if (count > 0) slices.add(sqrt(sum / count).toFloat())
        return shape(slices.toFloatArray())
    }

    /** [values] averaged down (or spread out) to [POINTS], scaled so the loud parts reach 1. */
    fun shape(values: FloatArray, points: Int = POINTS): FloatArray? {
        if (values.size < 4) return null
        val out = FloatArray(points) { bin ->
            val from = (bin.toLong() * values.size / points).toInt()
            val to = (((bin + 1).toLong() * values.size) / points).toInt().coerceAtLeast(from + 1).coerceAtMost(values.size)
            var total = 0f
            for (i in from until to) total += values[i]
            total / (to - from)
        }
        // The 95th percentile rather than the peak: one clipped hit shouldn't
        // flatten the rest of the song.
        val top = out.sorted()[(points * 0.95).toInt().coerceAtMost(points - 1)]
        if (top <= 0f) return null
        for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f).toDouble().pow(0.8).toFloat()
        return out
    }
}
