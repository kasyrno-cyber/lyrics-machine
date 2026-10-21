package com.lyricsmachine.core.playback

import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.lyrics.LyricsParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LrcTimingsTest {
    private fun parse(raw: String) = LyricsParser.parse(raw)

    @Test fun `each timed line ends where the next begins`() {
        val l = parse("[00:01.00]blue river walks\n[00:05.50]over quiet stones\n[00:09.00]home again")
        val t = LrcTimings.fromLyrics(l)
        assertEquals(listOf(LineTiming(0, 1000, 5500), LineTiming(1, 5500, 9000), LineTiming(2, 9000, 13000)), t)
    }

    @Test fun `last line uses endMs when given`() {
        val l = parse("[00:01.00]blue river walks\n[00:05.00]over quiet stones")
        val t = LrcTimings.fromLyrics(l, endMs = 8000)
        assertEquals(LineTiming(1, 5000, 8000), t.last())
    }

    @Test fun `untimed and spacer lines get no timing`() {
        val l = parse("[00:01.00]blue river walks\n\nplain line without tag\n[00:07.00]quiet stones")
        val t = LrcTimings.fromLyrics(l)
        assertEquals(2, t.size)
        assertEquals(l.lines.first { !it.isSpacer }.index, t[0].lineIndex)
        assertEquals(7000L, t[0].endMs)
    }

    @Test fun `plain lyrics produce nothing`() {
        assertTrue(LrcTimings.fromLyrics(parse("one line\nanother line")).isEmpty())
    }
}
