package com.lyricsmachine.core.playback

import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming

data class PlaybackPosition(val lineIndex: Int?, val wordIndex: Int?, val wordProgress: Float)

/** Maps a playback position (ms) to the current line/word using saved timings. */
class TimingLookup(lines: List<LineTiming>, words: List<WordTiming>) {
    private val lines: List<LineTiming> = lines.sortedBy { it.startMs }
    private val lineStarts: LongArray = LongArray(this.lines.size) { this.lines[it].startMs }
    private val lineByIndex: Map<Int, LineTiming> = this.lines.associateBy { it.lineIndex }
    private val wordsByLine: Map<Int, WordsOfLine> =
        words.groupBy { it.lineIndex }.mapValues { (_, ws) ->
            val sorted = ws.sortedBy { it.startMs }
            WordsOfLine(sorted, LongArray(sorted.size) { sorted[it].startMs })
        }

    private class WordsOfLine(val words: List<WordTiming>, val starts: LongArray)

    fun at(positionMs: Long): PlaybackPosition {
        val li = lastAtOrBefore(lineStarts, positionMs)
        if (li < 0) return PlaybackPosition(null, null, 0f)
        val line = lines[li]
        val wl = wordsByLine[line.lineIndex] ?: return PlaybackPosition(line.lineIndex, null, 0f)
        val wi = lastAtOrBefore(wl.starts, positionMs)
        if (wi < 0) return PlaybackPosition(line.lineIndex, null, 0f)
        val w = wl.words[wi]
        val progress = if (w.endMs <= w.startMs) 1f
        else ((positionMs - w.startMs).toFloat() / (w.endMs - w.startMs)).coerceIn(0f, 1f)
        return PlaybackPosition(line.lineIndex, w.wordIndex, progress)
    }

    fun startOfLine(lineIndex: Int): Long? = lineByIndex[lineIndex]?.startMs

    /** Index of the last element <= value, or -1. */
    private fun lastAtOrBefore(a: LongArray, value: Long): Int {
        var lo = 0
        var hi = a.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] <= value) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
