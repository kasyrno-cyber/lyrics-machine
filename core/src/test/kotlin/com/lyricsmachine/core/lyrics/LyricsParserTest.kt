package com.lyricsmachine.core.lyrics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class LyricsParserTest {
    @Test fun `normalize strips punctuation and case`() = assertEquals("hello", LyricsParser.normalize("Hello,"))
    @Test fun `normalize joins malay reduplication`() = assertEquals("kupukupu", LyricsParser.normalize("Kupu-kupu"))
    @Test fun `normalize keeps digits and non-latin letters`() {
        assertEquals("abc123", LyricsParser.normalize("ABC-123!"))
        assertEquals("café", LyricsParser.normalize("Café"))
        assertEquals("", LyricsParser.normalize("🎵"))
    }

    @Test fun `plain text gives one line per row`() {
        val l = LyricsParser.parse("the lantern glows\non the quiet river")
        assertEquals(2, l.lines.size); assertEquals(7, l.tokens.size)
        assertEquals("lantern", l.tokens[1].token)
    }

    @Test fun `section labels and blanks are spacers excluded from tokens`() {
        val l = LyricsParser.parse("[Chorus]\nbulan terang\n\n(Bridge)\ndi atas bukit")
        assertEquals(listOf(true, false, true, true, false), l.lines.map { it.isSpacer })
        assertEquals(5, l.tokens.size); assertNull(l.firstTokenOfLine(0)); assertEquals(2, l.firstTokenOfLine(4))
    }

    @Test fun `lrc timestamps parsed`() {
        val l = LyricsParser.parse("[00:12.50]the lantern glows\n[01:02.345]on the river")
        assertEquals(12_500L, l.lines[0].lrcStartMs); assertEquals(62_345L, l.lines[1].lrcStartMs)
        assertEquals("the", l.lines[0].words[0].display)
    }

    @Test fun `lrc timestamp without fraction and with colon separator`() {
        val l = LyricsParser.parse("[00:07]one\n[00:08:25]two")
        assertEquals(7_000L, l.lines[0].lrcStartMs); assertEquals(8_250L, l.lines[1].lrcStartMs)
    }

    @Test fun `timestamp-only line is a spacer keeping its start`() {
        val l = LyricsParser.parse("[00:30.00]\n[00:31.00]hello")
        assertEquals(true, l.lines[0].isSpacer); assertEquals(30_000L, l.lines[0].lrcStartMs)
        assertEquals(false, l.lines[1].isSpacer); assertEquals(1, l.tokens.size)
    }

    @Test fun `parse handles CRLF, whitespace, emoji and lrc metadata`() {
        val l = LyricsParser.parse("[ar:Someone]\r\n[ti:Song]\r\n  hati  saya 🎵 \r\n")
        assertEquals(1, l.lines.size); assertEquals(listOf("hati", "saya"), l.tokens.map { it.token })
    }

    @Test fun `metadata tags are case-insensitive and lines renumbered`() {
        val l = LyricsParser.parse("[AR:Someone]\n[Offset:+200]\n[length: 03:20]\n[al:X]\n[by:me]\nfirst\nsecond")
        assertEquals(2, l.lines.size); assertEquals(listOf(0, 1), l.lines.map { it.index })
    }

    @Test fun `empty-token words stay out of words and token refs index into words`() {
        val l = LyricsParser.parse("🎵 hati - saya")
        assertEquals(listOf("hati", "saya"), l.lines[0].words.map { it.display })
        assertEquals(1, l.tokens[1].wordIndex)
    }

    @Test fun `line of only symbols is not a spacer but has no tokens`() {
        val l = LyricsParser.parse("🎵🎵\nhi")
        assertEquals(false, l.lines[0].isSpacer); assertEquals(1, l.tokens.size)
        assertNull(l.firstTokenOfLine(0)); assertEquals(0, l.firstTokenOfLine(1))
    }

    @Test fun `lineOfToken maps back`() {
        val l = LyricsParser.parse("a b\nc"); assertEquals(1, l.lineOfToken(2))
    }

    @Test fun `lineOfToken out of range throws`() {
        val l = LyricsParser.parse("a b\nc")
        assertThrows(IndexOutOfBoundsException::class.java) { l.lineOfToken(3) }
        assertThrows(IndexOutOfBoundsException::class.java) { l.lineOfToken(-1) }
    }

    @Test fun `empty input and trailing blanks give no extra lines`() {
        val l = LyricsParser.parse("")
        assertEquals(1, LyricsParser.parse("a\n\n\n").lines.size)
        assertEquals(0, l.lines.size); assertEquals(0, l.tokens.size)
    }
}
