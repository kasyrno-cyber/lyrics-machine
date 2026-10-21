package com.lyricsmachine.core.lyrics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class VersesTest {
    private fun verses(text: String) = Verses.of(LyricsParser.parse(text.trimIndent()))

    @Test fun `blank line splits verses`() {
        val v = verses(
            """
            Amber kites above the harbour
            Silent bells in morning mist

            Lanterns hum along the quay
            Slow tides carry us home
            """,
        )
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), v.map { it.lineIndices })
        assertEquals(listOf(0, 1), v.map { it.index })
        assertTrue(v.all { it.label == null })
    }

    @Test fun `label line splits and attaches its label to the following verse`() {
        val v = verses(
            """
            Amber kites above the harbour
            Silent bells in morning mist
            [Chorus]
            Lanterns hum along the quay
            Slow tides carry us home
            """,
        )
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), v.map { it.lineIndices })
        assertEquals(listOf<String?>(null, "[Chorus]"), v.map { it.label })
    }

    @Test fun `label survives a blank line before the verse`() {
        val v = verses(
            """
            [Bridge]

            Copper rivers wander on
            """,
        )
        assertEquals(1, v.size)
        assertEquals("[Bridge]", v[0].label)
    }

    @Test fun `consecutive spacers make no empty verses`() {
        val v = verses(
            """
            First stitch of the quilt


            [Verse]

            Second stitch of the quilt
            """,
        )
        assertEquals(listOf(listOf(0), listOf(5)), v.map { it.lineIndices })
        assertEquals(listOf<String?>(null, "[Verse]"), v.map { it.label })
    }

    @Test fun `leading and trailing spacers are ignored`() {
        val v = verses(
            """
            [Intro]

            Only verse of the tune
            Second line of it
            (hum)

            """,
        )
        assertEquals(1, v.size)
        assertEquals(listOf(2, 3), v[0].lineIndices)
        assertEquals("[Intro]", v[0].label)
    }

    @Test fun `no spacers falls back to groups of four`() {
        val text = (0 until 10).joinToString("\n") { "Invented line number $it" }
        val v = verses(text)
        assertEquals(listOf(4, 4, 2), v.map { it.lineIndices.size })
        assertEquals(listOf(0, 1, 2, 3), v[0].lineIndices)
        assertEquals(listOf(8, 9), v[2].lineIndices)
        assertEquals(listOf(0, 1, 2), v.map { it.index })
    }

    @Test fun `short song without spacers is one verse`() {
        val v = verses("One line\nTwo lines\nThree lines\nFour lines")
        assertEquals(1, v.size)
        assertEquals(4, v[0].lineIndices.size)
    }

    @Test fun `songs with spacers do not use the fallback`() {
        val text = (0 until 6).joinToString("\n") { "Invented line number $it" } + "\n\nTail line"
        assertEquals(listOf(6, 1), verses(text).map { it.lineIndices.size })
    }

    @Test fun `empty lyrics have no verses`() {
        assertTrue(Verses.of(Lyrics(emptyList())).isEmpty())
        assertTrue(verses("[Intro]\n\n(hum)").isEmpty())
    }

    @Test fun `verseOfLine for lyric line, spacer and out of range`() {
        val v = verses(
            """
            [Intro]
            Alpha line
            Beta line

            [Chorus]
            Gamma line
            (hum)
            """,
        )
        // indices: 0 [Intro], 1 Alpha, 2 Beta, 3 blank, 4 [Chorus], 5 Gamma, 6 (hum)
        assertEquals(0, Verses.verseOfLine(v, 1))
        assertEquals(0, Verses.verseOfLine(v, 2))
        assertEquals(1, Verses.verseOfLine(v, 5))
        assertEquals(0, Verses.verseOfLine(v, 0)) // spacer -> next verse
        assertEquals(1, Verses.verseOfLine(v, 3))
        assertEquals(1, Verses.verseOfLine(v, 4))
        assertNull(Verses.verseOfLine(v, 6)) // trailing spacer, no next verse
        assertNull(Verses.verseOfLine(v, 99))
        assertNull(Verses.verseOfLine(v, -1))
    }
}
