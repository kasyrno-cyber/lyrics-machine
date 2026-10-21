package com.lyricsmachine.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingResamplerTest {
    private fun totalOutput(rate: Int, seconds: Int, piece: Int = 1024): Pair<Long, Long> {
        val r = StreamingResampler(rate)
        val frames = rate.toLong() * seconds
        var fed = 0L
        var out = 0L
        val buf = FloatArray(piece)
        while (fed < frames) {
            val n = minOf(piece.toLong(), frames - fed).toInt()
            out += r.process(if (n == piece) buf else FloatArray(n)).size
            fed += n
        }
        return out to frames * AudioMath.TARGET_RATE / rate
    }

    @Test fun `44100 in 1024 frame pieces for 4 minutes stays within one sample`() {
        val (actual, exact) = totalOutput(44_100, 240)
        assertTrue(abs(actual - exact) <= 1, "actual=$actual exact=$exact")
    }

    @Test fun `48000 in 1024 frame pieces for 4 minutes stays within one sample`() {
        val (actual, exact) = totalOutput(48_000, 240)
        assertTrue(abs(actual - exact) <= 1, "actual=$actual exact=$exact")
    }

    @Test fun `piecewise sine matches one-shot resampling`() {
        val rate = 44_100
        val input = FloatArray(rate) { sin(2 * PI * 440 * it / rate).toFloat() }
        val oneShot = AudioMath.resample(input, rate)
        val r = StreamingResampler(rate)
        val pieces = ArrayList<Float>()
        var i = 0
        val sizes = intArrayOf(1024, 7, 333, 1, 2048)
        var s = 0
        while (i < input.size) {
            val n = minOf(sizes[s++ % sizes.size], input.size - i)
            pieces += r.process(input.copyOfRange(i, i + n)).toList()
            i += n
        }
        assertTrue(abs(pieces.size - oneShot.size) <= 1, "sizes ${pieces.size} vs ${oneShot.size}")
        for (k in 0 until minOf(pieces.size, oneShot.size) - 1) {
            assertTrue(abs(pieces[k] - oneShot[k]) < 1e-3f, "sample $k: ${pieces[k]} vs ${oneShot[k]}")
        }
    }

    @Test fun `equal rates pass through`() {
        val r = StreamingResampler(16_000)
        val a = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertArrayEquals(a, r.process(a))
        assertArrayEquals(floatArrayOf(0.5f), r.process(floatArrayOf(0.5f)))
    }
}
