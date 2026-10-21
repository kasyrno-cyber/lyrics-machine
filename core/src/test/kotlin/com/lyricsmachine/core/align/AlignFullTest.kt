package com.lyricsmachine.core.align

import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.HeardWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlignFullTest {
    private fun heard(vararg pairs: Pair<String, Long>) =
        pairs.map { (t, s) -> HeardWord(t, s, s + 300, 0.9f) }

    private val lyrics = LyricsParser.parse("the lantern glows\non the quiet river")
    private val perfect = heard(
        "the" to 1000, "lantern" to 1500, "glows" to 2000,
        "on" to 4000, "the" to 4500, "quiet" to 5000, "river" to 5500,
    )

    @Test fun `perfect transcript gives exact timings and ratio 1`() {
        val a = Aligner.alignFull(lyrics, perfect)
        assertEquals(1000L, a.lines[0].startMs)
        assertEquals(4000L, a.lines[1].startMs)
        assertEquals(5800L, a.lines[1].endMs)
        assertEquals(1.0, a.matchRatio)
        assertFalse(a.isLowQuality)
    }

    @Test fun `missing word is interpolated between neighbours`() {
        val a = Aligner.alignFull(lyrics, perfect.filter { it.text != "quiet" })
        val w = a.words.first { it.lineIndex == 1 && it.wordIndex == 2 }
        assertFalse(w.matched)
        assertTrue(w.startMs >= 4800 && w.startMs < 5500)
    }

    @Test fun `misheard word still matches`() {
        val h = perfect.map { if (it.text == "lantern") it.copy(text = "lanturn") else it }
        val a = Aligner.alignFull(lyrics, h)
        assertTrue(a.words.first { it.lineIndex == 0 && it.wordIndex == 1 }.matched)
    }

    @Test fun `joined heard words match split lyric`() {
        val l = LyricsParser.parse("sayangku")
        val a = Aligner.alignFull(l, heard("sayang" to 1000, "ku" to 1300))
        assertTrue(a.words[0].matched)
        assertEquals(1000L, a.words[0].startMs)
        assertEquals(1600L, a.words[0].endMs)
    }

    @Test fun `split heard word matches two lyric tokens`() {
        val l = LyricsParser.parse("sayang ku")
        val a = Aligner.alignFull(l, heard("sayangku" to 1000))
        assertTrue(a.words.all { it.matched })
        assertEquals(1000L, a.words[0].startMs)
        assertTrue(a.words[1].startMs in 1001..1299)
        assertEquals(1300L, a.words[1].endMs)
    }

    @Test fun `extra hallucinated words are ignored`() {
        val h = heard(
            "um" to 500, "the" to 1000, "lantern" to 1500, "glows" to 2000, "yeah" to 3000,
            "on" to 4000, "the" to 4500, "quiet" to 5000, "river" to 5500,
        )
        val a = Aligner.alignFull(lyrics, h)
        assertEquals(1.0, a.matchRatio)
        assertEquals(1000L, a.lines[0].startMs)
    }

    @Test fun `punctuation-only heard words are dropped`() {
        val h = heard("..." to 100) + perfect
        assertEquals(1.0, Aligner.alignFull(lyrics, h).matchRatio)
    }

    @Test fun `repeated chorus aligns to correct occurrence`() {
        val l = LyricsParser.parse("rise up high\nwalk the road\nrise up high")
        val h = heard(
            "rise" to 1000, "up" to 1400, "high" to 1800,
            "walk" to 5000, "the" to 5400, "road" to 5800,
            "rise" to 9000, "up" to 9400, "high" to 9800,
        )
        val a = Aligner.alignFull(l, h)
        assertEquals(1000L, a.lines[0].startMs)
        assertEquals(5000L, a.lines[1].startMs)
        assertEquals(9000L, a.lines[2].startMs)
    }

    @Test fun `repeated chorus with only second occurrence heard`() {
        val l = LyricsParser.parse("rise up high\nwalk the road\nrise up high")
        val h = heard("walk" to 5000, "the" to 5400, "road" to 5800, "rise" to 9000, "up" to 9400, "high" to 9800)
        val a = Aligner.alignFull(l, h)
        assertEquals(5000L, a.lines[1].startMs)
        assertEquals(9000L, a.lines[2].startMs)
        assertTrue(a.lines[0].startMs < 5000)
    }

    @Test fun `leading run gets 400ms each ending at first match`() {
        val a = Aligner.alignFull(lyrics, perfect.drop(2))
        // "the lantern" unmatched, "glows" matched at 2000
        assertEquals(1200L, a.words[0].startMs)
        assertEquals(1600L, a.words[1].startMs)
        assertEquals(2000L, a.words[1].endMs)
    }

    @Test fun `leading run clamps at zero`() {
        val a = Aligner.alignFull(lyrics, perfect.drop(2).map { it.copy(startMs = it.startMs - 1700, endMs = it.endMs - 1700) })
        assertTrue(a.words.all { it.startMs >= 0 })
    }

    @Test fun `trailing run gets 400ms each after last match`() {
        val a = Aligner.alignFull(lyrics, perfect.take(5))
        // last match "the" (4500..4800); quiet, river follow
        assertEquals(4800L, a.words[5].startMs)
        assertEquals(5200L, a.words[6].startMs)
        assertEquals(5600L, a.words[6].endMs)
    }

    @Test fun `alignFull returns empty timings and zero ratio when nothing matches`() {
        val a = Aligner.alignFull(lyrics, heard("xxxxx" to 1000, "zzzzz" to 2000))
        assertTrue(a.lines.isEmpty())
        assertTrue(a.words.isEmpty())
        assertEquals(0.0, a.matchRatio)
    }

    @Test fun `empty heard returns empty alignment`() {
        assertEquals(Alignment(emptyList(), emptyList(), 0.0), Aligner.alignFull(lyrics, emptyList()))
    }

    @Test fun `lyrics with no tokens return empty alignment`() {
        val l = LyricsParser.parse("\n\n")
        assertEquals(Alignment(emptyList(), emptyList(), 0.0), Aligner.alignFull(l, perfect))
        assertEquals(Alignment(emptyList(), emptyList(), 0.0), Aligner.alignFull(LyricsParser.parse(""), perfect))
    }

    @Test fun `low quality flagged below 0_40`() {
        val a = Aligner.alignFull(lyrics, perfect.take(2))
        assertTrue(a.matchRatio < 0.40)
        assertTrue(a.isLowQuality)
    }

    @Test fun `spacer lines get no timing`() {
        val l = LyricsParser.parse("the lantern glows\n\non the quiet river")
        val a = Aligner.alignFull(l, perfect)
        assertEquals(listOf(0, 2), a.lines.map { it.lineIndex })
    }

    @Test fun `start times are monotonic`() {
        val h = heard("the" to 1000, "glows" to 2000, "on" to 4000, "river" to 5500)
        val a = Aligner.alignFull(lyrics, h)
        a.words.zipWithNext().forEach { (x, y) -> assertTrue(x.startMs <= y.startMs) }
        a.words.forEach { assertTrue(it.endMs >= it.startMs) }
    }

    @Test fun `600 by 800 case is fast`() {
        val words = List(600) { "w${it}x${it * 7 % 13}" }
        val l = LyricsParser.parse(words.chunked(6).joinToString("\n") { it.joinToString(" ") })
        val h = ArrayList<HeardWord>()
        var t = 0L
        for (i in 0 until 800) {
            val txt = if (i % 4 == 3) "filler$i" else words[(i - i / 4) % 600]
            h.add(HeardWord(txt, t, t + 200, 0.9f)); t += 250
        }
        val start = System.nanoTime()
        val a = Aligner.alignFull(l, h)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 2000, "took $ms ms")
        assertTrue(a.matchRatio > 0.9)
    }
}
