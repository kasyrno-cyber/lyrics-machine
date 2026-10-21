package com.lyricsmachine.core.audio

/**
 * Stateful linear-interpolation resampler for audio arriving in pieces (e.g. one codec buffer at a time).
 *
 * Output sample k is taken at input position k * fromRate / toRate, computed exactly in integers over the
 * whole stream, so lengths never drift: after N input frames the total output is within one sample of
 * N * toRate / fromRate (for downsampling; upsampling may lag by up to toRate / fromRate samples). The last
 * input sample is carried across calls, so interpolation is continuous across piece boundaries.
 * Like [AudioMath.resample], no anti-alias filter is applied. Equal rates pass through unchanged.
 */
class StreamingResampler(val fromRate: Int, val toRate: Int = AudioMath.TARGET_RATE) {
    init {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
    }

    private var consumed = 0L // input frames seen before the current piece
    private var nextOut = 0L // index of the next output sample
    private var last = 0f // input sample at index consumed - 1

    fun process(mono: FloatArray): FloatArray {
        if (fromRate == toRate) return mono.copyOf()
        if (mono.isEmpty()) return FloatArray(0)
        val available = consumed + mono.size // input indices < available are known
        val out = FloatArray(((mono.size.toLong() * toRate) / fromRate + 2).toInt())
        var n = 0
        while (true) {
            val num = nextOut * fromRate
            val i0 = num / toRate
            val i1 = i0 + 1
            if (i1 >= available) break
            val frac = (num % toRate).toFloat() / toRate
            val s0 = sampleAt(i0, mono)
            val s1 = sampleAt(i1, mono)
            if (n == out.size) break // cannot happen given the size bound; defensive
            out[n++] = s0 + (s1 - s0) * frac
            nextOut++
        }
        last = mono[mono.size - 1]
        consumed = available
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun sampleAt(index: Long, mono: FloatArray): Float =
        if (index < consumed) last else mono[(index - consumed).toInt()]
}
