package com.lyricsmachine.core.align

import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.HeardWord

object Aligner {
    internal const val MATCH_MULTIPLIER = 2.0
    internal const val MISMATCH_SCORE = -1.0
    internal const val LYRIC_GAP_SCORE = -1.0
    internal const val HEARD_GAP_SCORE = -0.5
    internal const val UNMATCHED_WORD_MS = 400L

    private const val DIAG: Byte = 1
    private const val JOIN: Byte = 2 // 2 heard : 1 lyric
    private const val SPLIT: Byte = 3 // 1 heard : 2 lyric
    private const val LYRIC_GAP: Byte = 4
    private const val HEARD_GAP: Byte = 5

    fun alignFull(lyrics: Lyrics, heard: List<HeardWord>): Alignment {
        val refs = lyrics.tokens
        val n = refs.size
        val hs = heard.filter { LyricsParser.normalize(it.text).isNotEmpty() }
        val ht = Array(hs.size) { LyricsParser.normalize(hs[it].text) }
        val m = hs.size
        if (n == 0 || m == 0) return EMPTY
        val lt = Array(n) { refs[it].token }

        val w = m + 1
        val score = DoubleArray((n + 1) * w)
        val move = ByteArray((n + 1) * w)
        for (j in 1..n) { score[j * w] = j * LYRIC_GAP_SCORE; move[j * w] = LYRIC_GAP }
        for (i in 1..m) { score[i] = i * HEARD_GAP_SCORE; move[i] = HEARD_GAP }

        for (j in 1..n) {
            for (i in 1..m) {
                val idx = j * w + i
                val s = Similarity.score(lt[j - 1], ht[i - 1])
                var best = score[(j - 1) * w + i - 1] + if (s >= Similarity.MATCH_THRESHOLD) MATCH_MULTIPLIER * s else MISMATCH_SCORE
                var mv = DIAG
                if (i >= 2) {
                    val js = Similarity.score(lt[j - 1], ht[i - 2] + ht[i - 1])
                    if (js >= Similarity.MATCH_THRESHOLD) {
                        val c = score[(j - 1) * w + i - 2] + MATCH_MULTIPLIER * js
                        if (c > best) { best = c; mv = JOIN }
                    }
                }
                if (j >= 2) {
                    val ss = Similarity.score(lt[j - 2] + lt[j - 1], ht[i - 1])
                    if (ss >= Similarity.MATCH_THRESHOLD) {
                        val c = score[(j - 2) * w + i - 1] + MATCH_MULTIPLIER * ss
                        if (c > best) { best = c; mv = SPLIT }
                    }
                }
                val lg = score[(j - 1) * w + i] + LYRIC_GAP_SCORE
                if (lg > best) { best = lg; mv = LYRIC_GAP }
                val hg = score[j * w + i - 1] + HEARD_GAP_SCORE
                if (hg > best) { best = hg; mv = HEARD_GAP }
                score[idx] = best
                move[idx] = mv
            }
        }

        // Traceback: per lyric token, matched time span (or -1).
        val starts = LongArray(n) { -1L }
        val ends = LongArray(n) { -1L }
        val matched = BooleanArray(n)
        var j = n
        var i = m
        while (j > 0 || i > 0) {
            when (move[j * w + i]) {
                DIAG -> {
                    val s = Similarity.score(lt[j - 1], ht[i - 1])
                    if (s >= Similarity.MATCH_THRESHOLD) {
                        matched[j - 1] = true
                        starts[j - 1] = hs[i - 1].startMs
                        ends[j - 1] = hs[i - 1].endMs
                    }
                    j--; i--
                }
                JOIN -> {
                    matched[j - 1] = true
                    starts[j - 1] = hs[i - 2].startMs
                    ends[j - 1] = hs[i - 1].endMs
                    j--; i -= 2
                }
                SPLIT -> {
                    val a = lt[j - 2].length
                    val b = lt[j - 1].length
                    val hw = hs[i - 1]
                    val mid = hw.startMs + (hw.endMs - hw.startMs) * a / (a + b)
                    matched[j - 2] = true; starts[j - 2] = hw.startMs; ends[j - 2] = mid
                    matched[j - 1] = true; starts[j - 1] = mid; ends[j - 1] = hw.endMs
                    j -= 2; i--
                }
                LYRIC_GAP -> j--
                else -> i--
            }
        }

        val matchedCount = matched.count { it }
        if (matchedCount == 0) return EMPTY
        interpolate(starts, ends, matched)

        // Monotonic non-decreasing starts; end >= start.
        var prev = 0L
        for (k in 0 until n) {
            if (starts[k] < prev) starts[k] = prev
            if (ends[k] < starts[k]) ends[k] = starts[k]
            prev = starts[k]
        }

        val words = ArrayList<WordTiming>(n)
        val lines = ArrayList<LineTiming>()
        var k = 0
        while (k < n) {
            val line = refs[k].lineIndex
            val first = k
            while (k < n && refs[k].lineIndex == line) {
                words.add(WordTiming(line, refs[k].wordIndex, starts[k], ends[k], matched[k]))
                k++
            }
            lines.add(LineTiming(line, starts[first], maxOf(ends[first], ends[k - 1])))
        }
        return Alignment(lines, words, matchedCount.toDouble() / n)
    }

    /**
     * Smith-Waterman local alignment of [heardTokens] (normalised) against
     * lyrics.tokens[fromToken until toToken]. Null if fewer than 2 matched words.
     */
    fun locate(lyrics: Lyrics, heardTokens: List<String>, fromToken: Int, toToken: Int, preferAfter: Int): LocateResult? {
        val refs = lyrics.tokens
        val from = fromToken.coerceIn(0, refs.size)
        val to = toToken.coerceIn(0, refs.size)
        val ht = heardTokens.filter { it.isNotEmpty() }
        val m = ht.size
        val n = to - from
        if (n <= 0 || m == 0) return null

        val w = m + 1
        val score = DoubleArray((n + 1) * w)
        val move = ByteArray((n + 1) * w) // 0 = path starts here
        val diagMatch = BooleanArray((n + 1) * w)
        val colBest = DoubleArray(n + 1)
        val colBestRow = IntArray(n + 1)
        for (j in 1..n) {
            val lt = refs[from + j - 1].token
            for (i in 1..m) {
                val idx = j * w + i
                val s = Similarity.score(lt, ht[i - 1])
                val isMatch = s >= Similarity.MATCH_THRESHOLD
                var best = score[(j - 1) * w + i - 1] + if (isMatch) MATCH_MULTIPLIER * s else MISMATCH_SCORE
                var mv = DIAG
                val lg = score[(j - 1) * w + i] + LYRIC_GAP_SCORE
                if (lg > best) { best = lg; mv = LYRIC_GAP }
                val hg = score[j * w + i - 1] + HEARD_GAP_SCORE
                if (hg > best) { best = hg; mv = HEARD_GAP }
                if (best <= 0.0) { best = 0.0; mv = 0 }
                score[idx] = best
                move[idx] = mv
                diagMatch[idx] = isMatch
                if (best > colBest[j]) { colBest[j] = best; colBestRow[j] = i }
            }
        }

        val top = colBest.max()
        if (top <= 0.0) return null
        // Tie-break: smallest end >= preferAfter, else largest end below it.
        var chosen = -1
        for (j in 1..n) {
            if (top - colBest[j] > 1e-9) continue
            val end = from + j
            chosen = when {
                chosen < 0 -> j
                end >= preferAfter && from + chosen < preferAfter -> j
                end >= preferAfter && from + chosen >= preferAfter -> chosen // keep smallest
                from + chosen >= preferAfter -> chosen
                else -> j // both below: keep largest
            }
        }

        var j = chosen
        var i = colBestRow[chosen]
        var matched = 0
        var run = 0
        var longestRun = 0
        while (j > 0 && i > 0 && score[j * w + i] > 0.0) {
            val step = move[j * w + i]
            val isMatchStep = step == DIAG && diagMatch[j * w + i]
            if (isMatchStep) { matched++; run++; if (run > longestRun) longestRun = run } else run = 0
            when (step) {
                DIAG -> { j--; i-- }
                LYRIC_GAP -> j--
                HEARD_GAP -> i--
                else -> break
            }
        }
        if (matched < 2) return null
        val confidence = (colBest[chosen] / (MATCH_MULTIPLIER * m)).coerceIn(0.0, 1.0)
        return LocateResult(from + chosen, matched, confidence, longestRun)
    }

    private fun interpolate(starts: LongArray, ends: LongArray, matched: BooleanArray) {
        val n = starts.size
        var k = 0
        var prevEnd = -1L
        while (k < n) {
            if (matched[k]) { prevEnd = ends[k]; k++; continue }
            val runStart = k
            while (k < n && !matched[k]) k++
            val count = k - runStart
            if (prevEnd < 0) { // leading run
                val firstStart = starts[k]
                for (t in 0 until count) {
                    val s = maxOf(0L, firstStart - (count - t) * UNMATCHED_WORD_MS)
                    starts[runStart + t] = s
                    ends[runStart + t] = maxOf(s, if (t == count - 1) firstStart else firstStart - (count - t - 1) * UNMATCHED_WORD_MS)
                }
            } else if (k >= n) { // trailing run
                for (t in 0 until count) {
                    starts[runStart + t] = prevEnd + t * UNMATCHED_WORD_MS
                    ends[runStart + t] = prevEnd + (t + 1) * UNMATCHED_WORD_MS
                }
            } else { // between two matches
                val span = maxOf(0L, starts[k] - prevEnd)
                for (t in 0 until count) {
                    starts[runStart + t] = prevEnd + span * t / count
                    ends[runStart + t] = prevEnd + span * (t + 1) / count
                }
            }
        }
    }

    private val EMPTY = Alignment(emptyList(), emptyList(), 0.0)
}
