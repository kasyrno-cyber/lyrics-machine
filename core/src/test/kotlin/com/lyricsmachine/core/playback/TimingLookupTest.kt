package com.lyricsmachine.core.playback

import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimingLookupTest {
    private val lines = listOf(LineTiming(0, 1000, 3000), LineTiming(1, 4000, 6000))
    private val words = listOf(
        WordTiming(0, 0, 1000, 2000, true), WordTiming(0, 1, 2000, 3000, true),
        WordTiming(1, 0, 4000, 5000, true), WordTiming(1, 1, 5000, 6000, true),
    )
    private val lookup = TimingLookup(lines, words)

    @Test fun `before first line is null`() {
        assertEquals(PlaybackPosition(null, null, 0f), lookup.at(500))
    }

    @Test fun `inside a line returns it`() {
        val p = lookup.at(2100)
        assertEquals(0, p.lineIndex)
        assertEquals(1, p.wordIndex)
    }

    @Test fun `between lines keeps previous line`() {
        val p = lookup.at(3500)
        assertEquals(0, p.lineIndex)
        assertEquals(1, p.wordIndex)
        assertEquals(1f, p.wordProgress)
    }

    @Test fun `word progress is 0_5 at word midpoint`() {
        assertEquals(0.5f, lookup.at(4500).wordProgress, 0.001f)
        assertEquals(0, lookup.at(4500).wordIndex)
    }

    @Test fun `startOfLine returns seek target`() {
        assertEquals(4000L, lookup.startOfLine(1))
        assertNull(lookup.startOfLine(9))
    }

    @Test fun `empty timings always null`() {
        val l = TimingLookup(emptyList(), emptyList())
        assertEquals(PlaybackPosition(null, null, 0f), l.at(1234))
        assertNull(l.startOfLine(0))
    }

    @Test fun `out of order input is sorted`() {
        val l = TimingLookup(lines.reversed(), words.reversed())
        assertEquals(0, l.at(2100).lineIndex)
        assertEquals(1, l.at(5500).wordIndex)
        assertEquals(1000L, l.startOfLine(0))
    }

    @Test fun `line index gap from spacer`() {
        val l = TimingLookup(
            listOf(LineTiming(0, 0, 1000), LineTiming(2, 2000, 3000)),
            listOf(WordTiming(0, 0, 0, 1000, true), WordTiming(2, 0, 2000, 3000, true)),
        )
        assertEquals(0, l.at(1500).lineIndex)
        assertEquals(2, l.at(2500).lineIndex)
        assertNull(l.startOfLine(1))
    }

    @Test fun `no words in line or before first word gives null word`() {
        val l = TimingLookup(
            listOf(LineTiming(0, 1000, 3000)),
            listOf(WordTiming(0, 0, 1500, 2000, true)),
        )
        assertEquals(PlaybackPosition(0, null, 0f), l.at(1200))
        assertEquals(PlaybackPosition(0, null, 0f), TimingLookup(listOf(LineTiming(0, 0, 10)), emptyList()).at(5))
    }

    @Test fun `zero length word progress is 1`() {
        val l = TimingLookup(listOf(LineTiming(0, 0, 10)), listOf(WordTiming(0, 0, 5, 5, true)))
        assertEquals(1f, l.at(5).wordProgress)
    }
}
