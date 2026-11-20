package com.music.bitchord

import com.music.bitchord.playback.smart.SeekWaveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekWaveformTest {
    @Test
    fun `shapes any length down to the point count, loud parts at the top`() {
        val quietThenLoud = FloatArray(1000) { if (it < 500) 0.1f else 0.8f }
        val shaped = SeekWaveform.shape(quietThenLoud)
        assertNotNull(shaped)
        assertEquals(SeekWaveform.POINTS, shaped!!.size)
        assertEquals(1f, shaped.last(), 0.001f)
        assertTrue(shaped.first() < 0.3f)
        assertTrue(shaped.all { it in 0f..1f })
    }

    @Test
    fun `spreads a short curve out`() {
        val shaped = SeekWaveform.shape(floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f), points = 20)
        assertEquals(20, shaped!!.size)
        assertTrue(shaped.first() < shaped.last())
    }

    @Test
    fun `silence and too little are nothing`() {
        assertNull(SeekWaveform.shape(FloatArray(100)))
        assertNull(SeekWaveform.shape(floatArrayOf(1f, 1f)))
    }

    @Test
    fun `one clipped hit does not flatten the rest`() {
        val values = FloatArray(400) { 0.5f }.also { it[200] = 10f }
        val shaped = SeekWaveform.shape(values)!!
        assertTrue(shaped[0] > 0.9f)
    }
}
