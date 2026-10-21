package com.lyricsmachine.core.audio

/** Fixed-capacity ring buffer holding the most recent audio samples. Not thread-safe. */
class RollingBuffer(private val capacitySamples: Int) {
    init { require(capacitySamples > 0) { "capacity must be > 0" } }

    private val data = FloatArray(capacitySamples)
    private var writePos = 0
    private var size = 0

    fun append(samples: FloatArray) {
        val start = maxOf(0, samples.size - capacitySamples)
        for (i in start until samples.size) {
            data[writePos] = samples[i]
            writePos = (writePos + 1) % capacitySamples
        }
        size = minOf(capacitySamples, size + (samples.size - start))
    }

    /** The most recent min(appended, capacity) samples, oldest first, as a new array. */
    fun latest(): FloatArray {
        val out = FloatArray(size)
        val first = (writePos - size + capacitySamples) % capacitySamples
        for (i in 0 until size) out[i] = data[(first + i) % capacitySamples]
        return out
    }
}
