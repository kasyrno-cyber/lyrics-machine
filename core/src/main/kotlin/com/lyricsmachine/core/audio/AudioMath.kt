package com.lyricsmachine.core.audio

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure PCM helpers for preparing audio for the speech engine. */
object AudioMath {
    const val TARGET_RATE = 16_000
    const val SILENCE_RMS = 0.01f

    /** Averages interleaved 16-bit channels into mono floats in [-1, 1). */
    fun toMonoFloat(interleaved: ShortArray, channels: Int): FloatArray {
        require(channels >= 1) { "channels must be >= 1" }
        require(interleaved.size % channels == 0) { "sample count must be a multiple of channels" }
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            val base = f * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            out[f] = sum.toFloat() / channels / 32768f
        }
        return out
    }

    /**
     * Linear-interpolation resampler. No anti-alias low-pass filter is applied, so
     * downsampling can alias content above the new Nyquist; acceptable for speech input.
     */
    fun resample(mono: FloatArray, fromRate: Int, toRate: Int = TARGET_RATE): FloatArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
        if (fromRate == toRate) return mono.copyOf()
        if (mono.isEmpty()) return FloatArray(0)
        val outSize = (mono.size.toDouble() * toRate / fromRate).roundToInt()
        val out = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outSize) {
            val pos = i * step
            val i0 = min(floor(pos).toInt(), mono.size - 1)
            val i1 = min(i0 + 1, mono.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = mono[i0] + (mono[i1] - mono[i0]) * frac
        }
        return out
    }

    /** Root-mean-square level; 0 for an empty array. */
    fun rms(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size).toFloat()
    }
}
