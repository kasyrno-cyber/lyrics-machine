package com.lyricsmachine.core.align

data class WordTiming(val lineIndex: Int, val wordIndex: Int, val startMs: Long, val endMs: Long, val matched: Boolean)

data class LineTiming(val lineIndex: Int, val startMs: Long, val endMs: Long)

data class Alignment(val lines: List<LineTiming>, val words: List<WordTiming>, val matchRatio: Double) {
    val isLowQuality: Boolean get() = matchRatio < LOW_QUALITY_RATIO

    companion object {
        const val LOW_QUALITY_RATIO = 0.40
    }
}
