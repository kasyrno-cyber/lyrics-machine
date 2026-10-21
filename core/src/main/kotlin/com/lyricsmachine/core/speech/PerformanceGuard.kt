package com.lyricsmachine.core.speech

/** Detects a device too slow for live mode: median of the last [window] inference times > [budgetMs]. */
class PerformanceGuard(private val budgetMs: Long, private val window: Int = 3) {
    init { require(window > 0) { "window must be > 0" } }

    private val samples = ArrayDeque<Long>()

    fun record(inferenceMs: Long) {
        samples.addLast(inferenceMs)
        while (samples.size > window) samples.removeFirst()
    }

    val overBudget: Boolean
        get() {
            if (samples.size < window) return false
            val s = samples.sorted()
            val median = if (s.size % 2 == 1) s[s.size / 2].toDouble()
            else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            return median > budgetMs
        }
}
