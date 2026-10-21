package com.lyricsmachine.core.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class AudioMathTest {
    @Test fun `stereo averages to mono`() {
        val out = AudioMath.toMonoFloat(shortArrayOf(16384, -16384, 32767, 0), 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(32767f / 2 / 32768f, out[1], 1e-6f)
    }

    @Test fun `mono passes through scaled`() {
        val out = AudioMath.toMonoFloat(shortArrayOf(-32768, 16384), 1)
        assertEquals(-1f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
    }

    @Test fun `toMonoFloat validates input`() {
        assertThrows<IllegalArgumentException> { AudioMath.toMonoFloat(shortArrayOf(1, 2, 3), 2) }
        assertThrows<IllegalArgumentException> { AudioMath.toMonoFloat(shortArrayOf(1), 0) }
    }

    @Test fun `resample 48k to 16k keeps duration`() {
        val input = FloatArray(48_000) { sin(2 * PI * 440 * it / 48_000).toFloat() }
        val out = AudioMath.resample(input, 48_000)
        assertEquals(16_000, out.size)
        assertEquals(sin(2 * PI * 440 * 100 / 16_000).toFloat(), out[100], 0.02f)
    }

    @Test fun `resample same rate returns equal copy`() {
        val input = floatArrayOf(0.1f, 0.2f)
        val out = AudioMath.resample(input, 16_000)
        assertArrayEquals(input, out)
        assertNotSame(input, out)
    }

    @Test fun `resample upsamples and validates rates`() {
        assertEquals(30, AudioMath.resample(FloatArray(10), 8_000, 24_000).size)
        assertEquals(0, AudioMath.resample(FloatArray(0), 8_000).size)
        assertThrows<IllegalArgumentException> { AudioMath.resample(FloatArray(4), 0) }
        assertThrows<IllegalArgumentException> { AudioMath.resample(FloatArray(4), 8_000, -1) }
    }

    @Test fun `rms of silence is below threshold`() {
        assertTrue(AudioMath.rms(FloatArray(1000)) < AudioMath.SILENCE_RMS)
        assertEquals(0f, AudioMath.rms(FloatArray(0)))
    }

    @Test fun `rms of sine is amplitude over root two`() {
        val s = FloatArray(16_000) { (0.5 * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        assertEquals(0.3536f, AudioMath.rms(s), 0.005f)
        assertTrue(AudioMath.rms(s) > AudioMath.SILENCE_RMS)
    }
}
