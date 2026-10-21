package com.lyricsmachine.core.align

import com.lyricsmachine.core.lyrics.LyricsParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocateTest {
    private val song = LyricsParser.parse("the lantern glows\non the quiet river\nwe sing till dawn")
    private fun words(s: String) = s.split(" ")

    @Test fun `finds snippet inside window`() {
        val r = Aligner.locate(song, words("on the quiet"), 0, song.tokens.size, 0)
        assertNotNull(r)
        assertEquals(6, r!!.endTokenIndex)
        assertEquals(3, r.matchedCount)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test fun `returns null for unrelated words`() {
        assertNull(Aligner.locate(song, words("zebra xylophone quartz"), 0, song.tokens.size, 0))
    }

    @Test fun `respects window bounds`() {
        assertNull(Aligner.locate(song, words("on the quiet"), 0, 3, 0))
        assertNull(Aligner.locate(song, words("on the quiet"), 6, song.tokens.size, 0))
        assertNull(Aligner.locate(song, words("on the quiet"), 5, 2, 0))
        val r = Aligner.locate(song, words("on the quiet"), -5, 100, 0)
        assertEquals(6, r!!.endTokenIndex)
    }

    @Test fun `locate prefers candidate at or after preferAfter`() {
        val l = LyricsParser.parse("rise up high\nwalk the road\nrise up high\nwalk the road\nrise up high")
        val h = words("rise up high")
        val n = l.tokens.size
        assertEquals(9, Aligner.locate(l, h, 0, n, 7)!!.endTokenIndex)
        assertEquals(15, Aligner.locate(l, h, 0, n, 10)!!.endTokenIndex)
        assertEquals(15, Aligner.locate(l, h, 0, n, 20)!!.endTokenIndex)
        assertEquals(3, Aligner.locate(l, h, 0, n, 0)!!.endTokenIndex)
    }

    @Test fun `tolerates one misheard word`() {
        val r = Aligner.locate(song, words("on a quiet river"), 0, song.tokens.size, 0)
        assertNotNull(r)
        assertEquals(7, r!!.endTokenIndex)
        assertTrue(r.confidence > 0.5)
    }

    @Test fun `empty heard or empty strings give null`() {
        assertNull(Aligner.locate(song, emptyList(), 0, song.tokens.size, 0))
        assertNull(Aligner.locate(song, listOf("", ""), 0, song.tokens.size, 0))
    }
}
