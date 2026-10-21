package com.lyricsmachine.core.tracker

import com.lyricsmachine.core.align.Aligner
import com.lyricsmachine.core.align.LocateResult
import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.HeardWord
import kotlin.math.floor

/**
 * Follows a live performance through [lyrics] from periodic speech chunks and extrapolates in between.
 * Time is injected as plain milliseconds; nothing here reads a clock.
 */
class LiveTracker(private val lyrics: Lyrics, private val config: TrackerConfig = TrackerConfig()) {
    private val tokenCount = lyrics.tokens.size
    /** For each line, the number of non-spacer lines before it. */
    private val lyricOrdinal: IntArray = IntArray(lyrics.lines.size).also { ord ->
        var n = 0
        for ((i, line) in lyrics.lines.withIndex()) { ord[i] = n; if (!line.isSpacer) n++ }
    }

    private var state = TrackerState.Listening
    private var basePos = 0.0
    private var baseMs = 0L
    private var pace = config.initialPaceTokensPerSec

    private var hasLocked = false
    private var lastLockMs = 0L
    private var lastLockLine = 0
    /** Previous chunk lock used for pace observations; cleared by a user jump. */
    private var paceAnchorPos: Double? = null
    private var paceAnchorMs = 0L
    /** Position set by the last lock or user jump; used as preferAfter (never the extrapolated position). */
    private var lockAnchorPos = 0.0
    /** Last time audio was present; null until the first chunk. */
    private var lastAudioMs: Long? = null

    fun onChunk(nowMs: Long, heard: List<HeardWord>, audioPresent: Boolean): TrackerSnapshot {
        materialise(nowMs)
        if (audioPresent || lastAudioMs == null) lastAudioMs = nowMs
        val lostDue = hasLocked && nowMs - lastLockMs >= config.lostAfterMs

        val wholeSong = !hasLocked || lostDue
        val result = locate(heard, wholeSong)
        if (result != null && isLock(result, wholeSong)) {
            applyLock(nowMs, result)
        } else {
            state = when {
                !audioPresent && nowMs - lastAudioMs!! >= config.silencePauseMs -> TrackerState.Paused
                !hasLocked -> TrackerState.Listening
                lostDue -> TrackerState.Lost
                else -> TrackerState.Coasting
            }
        }
        return snapshot(nowMs)
    }

    fun onUserJump(nowMs: Long, lineIndex: Int): TrackerSnapshot {
        if (tokenCount == 0) {
            basePos = 0.0
            baseMs = nowMs
            return snapshot(nowMs)
        }
        val start = firstTokenAtOrAfterLine(lineIndex.coerceIn(0, lyrics.lines.lastIndex)) ?: tokenCount
        basePos = start.toDouble()
        baseMs = nowMs
        lastLockMs = nowMs
        lastLockLine = lineAt(basePos)
        hasLocked = true
        paceAnchorPos = null
        lockAnchorPos = basePos
        state = TrackerState.Locked
        return snapshot(nowMs)
    }

    /** Pure: extrapolates for Locked/Coasting without changing any state. */
    fun snapshot(nowMs: Long): TrackerSnapshot {
        val pos = positionAt(nowMs)
        return TrackerSnapshot(state, pos, lineAt(pos))
    }

    /**
     * Token bounds [from, to) of the normal search window around [currentLine]. Only non-spacer lines
     * count: the window starts `windowLinesBack` lyric lines before [currentLine] and ends before the
     * (`windowLinesAhead` + 1)-th lyric line after it (or at the end of the song).
     */
    internal fun searchWindow(currentLine: Int): Pair<Int, Int> = searchWindow(currentLine, currentLine)

    /** Window whose back edge is measured from [backLine] and ahead edge from [aheadLine]. */
    private fun searchWindow(backLine: Int, aheadLine: Int): Pair<Int, Int> {
        var start = backLine
        var counted = 0
        var l = backLine - 1
        while (l >= 0 && counted < config.windowLinesBack) {
            if (!lyrics.lines[l].isSpacer) { start = l; counted++ }
            l--
        }
        val from = firstTokenAtOrAfterLine(start) ?: tokenCount
        val to = firstTokenOfNthLyricLineAfter(aheadLine, config.windowLinesAhead + 1) ?: tokenCount
        return from to to
    }

    private fun locate(heard: List<HeardWord>, wholeSong: Boolean): LocateResult? {
        if (tokenCount == 0) return null
        val words = heard.map { LyricsParser.normalize(it.text) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        // Back off by the snippet length so a short tail ending just before the anchor stays in this repeat.
        val preferAfter = if (!hasLocked) 0 else maxOf(0, floor(lockAnchorPos).toInt() - words.size)
        // Extrapolation can run ahead during line-end pauses, so the back edge is measured from whichever is
        // earlier: the extrapolated line or the last lock's anchor line. The ahead edge follows extrapolation.
        val (from, to) = if (wholeSong) 0 to tokenCount
        else lineAt(basePos).let { cur -> searchWindow(minOf(cur, lineAt(lockAnchorPos)), cur) }
        return Aligner.locate(lyrics, words, from, to, preferAfter)
    }

    /**
     * Whole-song searches need a run of `minLockTokens` consecutive matches. Windowed searches accept
     * `minLockTokens` matches or a confident pair, except that a target more than one lyric line past the
     * anchor's line also needs the consecutive run.
     */
    private fun isLock(r: LocateResult, wholeSong: Boolean): Boolean {
        val strongRun = r.longestRun >= config.minLockTokens
        if (wholeSong) return strongRun
        val basic = r.matchedCount >= config.minLockTokens ||
            (r.matchedCount >= 2 && r.confidence >= config.lockConfidence)
        if (!basic) return false
        val targetLine = lyrics.lineOfToken(r.endTokenIndex - 1)
        val farAhead = lyricOrdinal[targetLine] - lyricOrdinal[lineAt(lockAnchorPos)] > 1
        return !farAhead || strongRun
    }

    private fun applyLock(nowMs: Long, r: LocateResult) {
        val newPos = r.endTokenIndex.toDouble().coerceIn(0.0, tokenCount.toDouble())
        val anchor = paceAnchorPos
        if (anchor != null && nowMs > paceAnchorMs) {
            val obs = (newPos - anchor) / ((nowMs - paceAnchorMs) / 1000.0)
            if (obs in config.minPace..config.maxPace) {
                pace = pace * (1 - config.paceSmoothing) + obs * config.paceSmoothing
            }
        }
        paceAnchorPos = newPos
        lockAnchorPos = newPos
        paceAnchorMs = nowMs
        basePos = newPos
        baseMs = nowMs
        lastLockMs = nowMs
        lastLockLine = lyrics.lineOfToken(r.endTokenIndex - 1) // line of the last matched word
        hasLocked = true
        state = TrackerState.Locked
    }

    /** Freezes the extrapolated position as the new base before a chunk is applied. */
    private fun materialise(nowMs: Long) {
        basePos = positionAt(nowMs)
        baseMs = nowMs
    }

    private fun positionAt(nowMs: Long): Double {
        val advancing = state == TrackerState.Locked || state == TrackerState.Coasting
        if (!advancing) return basePos.coerceIn(0.0, tokenCount.toDouble())
        val elapsedSec = maxOf(0L, nowMs - baseMs) / 1000.0
        return minOf(basePos + pace * elapsedSec, coastCap()).coerceIn(0.0, tokenCount.toDouble())
    }

    /**
     * Extrapolation may not pass the first token of the (`maxCoastLines` + 1)-th lyric (non-spacer) line
     * after `lastLockLine`. It stops just short of it so the display never goes more than `maxCoastLines`
     * lyric lines past the last lock.
     */
    private fun coastCap(): Double {
        val boundary = firstTokenOfNthLyricLineAfter(lastLockLine, config.maxCoastLines + 1)
            ?: return tokenCount.toDouble()
        return maxOf(basePos, boundary - CAP_EPSILON)
    }

    private fun firstTokenAtOrAfterLine(line: Int): Int? {
        for (l in maxOf(0, line) until lyrics.lines.size) lyrics.firstTokenOfLine(l)?.let { return it }
        return null
    }

    /** First token of the [n]-th non-spacer line strictly after [line] (n >= 1); null if there is none. */
    private fun firstTokenOfNthLyricLineAfter(line: Int, n: Int): Int? {
        var seen = 0
        for (l in maxOf(0, line + 1) until lyrics.lines.size) {
            val first = lyrics.firstTokenOfLine(l) ?: continue
            if (++seen == n) return first
        }
        return null
    }

    private fun lineAt(pos: Double): Int =
        if (tokenCount == 0) 0 else lyrics.lineOfToken(minOf(floor(pos).toInt(), tokenCount - 1).coerceAtLeast(0))

    private companion object {
        const val CAP_EPSILON = 1e-6
    }
}
