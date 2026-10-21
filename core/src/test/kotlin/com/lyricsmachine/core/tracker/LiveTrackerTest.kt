package com.lyricsmachine.core.tracker

import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.HeardWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveTrackerTest {
    // Original lyric. Token ranges: line0 0-4, line1 5-10, line2 11-16, line3 17-22, line4 23-26, line5 27-32.
    private val song = LyricsParser.parse(
        """
        lanterns drift along quiet rivers
        light the night we sing together
        carry me home over the hills
        light the night we sing together
        stones remember every footstep
        light the night we sing together
        """.trimIndent()
    )
    private val opening = "lanterns drift along quiet rivers"
    private val chorus = "light the night we sing together"
    private val noise = "zebra xylophone quartz marble"

    private fun say(vararg words: String): List<HeardWord> =
        words.flatMap { it.split(" ") }.mapIndexed { i, w -> HeardWord(w, i * 300L, i * 300L + 250, 0.9f) }

    private fun tracker() = LiveTracker(song)

    @Test fun `locks on first confident phrase`() {
        val t = tracker()
        val s = t.onChunk(2_000, say(opening), audioPresent = true)
        assertEquals(TrackerState.Locked, s.state)
        assertEquals(5.0, s.tokenPosition, 1e-9)
        assertEquals(1, s.lineIndex)
    }

    @Test fun `stays listening on unrelated words`() {
        val t = tracker()
        val s = t.onChunk(2_000, say(noise), audioPresent = true)
        assertEquals(TrackerState.Listening, s.state)
        assertEquals(0.0, s.tokenPosition, 1e-9)
        val later = t.snapshot(9_000)
        assertEquals(TrackerState.Listening, later.state)
        assertEquals(0.0, later.tokenPosition, 1e-9)
        assertEquals(0, later.lineIndex)
    }

    @Test fun `snapshot extrapolates at pace while locked`() {
        val t = tracker()
        t.onChunk(1_000, say(opening), audioPresent = true)
        assertEquals(6.0, t.snapshot(1_500).tokenPosition, 1e-9)
        val s = t.snapshot(2_000)
        assertEquals(7.0, s.tokenPosition, 1e-9)
        assertEquals(TrackerState.Locked, s.state)
    }

    @Test fun `pace updates from consecutive locks`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true) // pos 5
        val s = t.onChunk(2_000, say(chorus), audioPresent = true) // first chorus, pos 11
        assertEquals(TrackerState.Locked, s.state)
        assertEquals(11.0, s.tokenPosition, 1e-9)
        // obs = 6 tokens / 2 s = 3.0; pace = 2.0*0.7 + 3.0*0.3 = 2.3
        assertEquals(13.3, t.snapshot(3_000).tokenPosition, 1e-9)
    }

    @Test fun `pace is not updated from a user jump`() {
        val t = tracker()
        t.onUserJump(0, 0)
        t.onChunk(1_000, say(opening), audioPresent = true) // obs would be 5 tok/s if counted
        assertEquals(7.0, t.snapshot(2_000).tokenPosition, 1e-9)
    }

    @Test fun `pauses after silence and does not advance`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true)
        val coasting = t.onChunk(2_000, emptyList(), audioPresent = false)
        assertEquals(TrackerState.Coasting, coasting.state)
        assertEquals(9.0, coasting.tokenPosition, 1e-9)
        val paused = t.onChunk(4_000, emptyList(), audioPresent = false)
        assertEquals(TrackerState.Paused, paused.state)
        assertEquals(13.0, paused.tokenPosition, 1e-9)
        assertEquals(13.0, t.snapshot(9_000).tokenPosition, 1e-9)
        // Silence wins over Lost: 12 s without a lock, still Paused and frozen.
        val still = t.onChunk(12_000, emptyList(), audioPresent = false)
        assertEquals(TrackerState.Paused, still.state)
        assertEquals(13.0, still.tokenPosition, 1e-9)
        // Audio returns, no lock, > lostAfterMs since the last lock → Lost.
        val lost = t.onChunk(14_000, say(noise), audioPresent = true)
        assertEquals(TrackerState.Lost, lost.state)
        assertEquals(13.0, lost.tokenPosition, 1e-9)
    }

    @Test fun `coasting is capped during instrumental`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true) // last matched word on line 0
        var s = t.snapshot(0)
        for (now in 2_000L..9_000L step 1_000) s = t.onChunk(now, emptyList(), audioPresent = true)
        assertEquals(TrackerState.Coasting, s.state)
        assertTrue(s.lineIndex <= 0 + 2, "line ${s.lineIndex}")
        assertTrue(s.tokenPosition <= 17.0)
        assertTrue(t.snapshot(60_000).lineIndex <= 2)
    }

    @Test fun `goes lost after 10s without lock and reacquires anywhere`() {
        val t = tracker()
        // Anchor 27 is on line 5; the window's back edge is one lyric line before it (line 4).
        assertEquals(27.0, t.onChunk(1_000, say("stones remember every footstep"), audioPresent = true).tokenPosition, 1e-9)
        for (now in listOf(3_000L, 5_000L, 7_000L)) {
            assertEquals(TrackerState.Coasting, t.onChunk(now, say(noise), audioPresent = true).state)
        }
        // 8 s since lock: the opening line is behind the search window, so it cannot lock yet.
        val notYet = t.onChunk(9_000, say(opening), audioPresent = true)
        assertEquals(TrackerState.Coasting, notYet.state)
        assertTrue(notYet.lineIndex >= 4)
        assertEquals(TrackerState.Lost, t.onChunk(11_000, say(noise), audioPresent = true).state)
        val back = t.onChunk(13_000, say(opening), audioPresent = true)
        assertEquals(TrackerState.Locked, back.state)
        assertEquals(5.0, back.tokenPosition, 1e-9)
    }

    @Test fun `tracker does not jump back to earlier chorus`() {
        val t = tracker()
        val locked = t.onChunk(0, say("carry me home over the hills light the night"), audioPresent = true)
        assertEquals(TrackerState.Locked, locked.state)
        assertEquals(20.0, locked.tokenPosition, 1e-9) // inside the 2nd chorus
        val s = t.onChunk(2_000, say(chorus), audioPresent = true)
        assertEquals(TrackerState.Locked, s.state)
        assertNotEquals(11.0, s.tokenPosition)
        // Re-hearing the current repeat stays in chorus 2 (no skip to chorus 3).
        assertEquals(23.0, s.tokenPosition, 1e-9)
        assertEquals(4, s.lineIndex)
    }

    @Test fun `moves forward to the next chorus when its preceding line is heard`() {
        val t = tracker()
        val locked = t.onChunk(0, say("$opening light the night"), audioPresent = true)
        assertEquals(8.0, locked.tokenPosition, 1e-9) // inside chorus 1
        t.onChunk(2_000, emptyList(), audioPresent = true) // coasting
        val s = t.onChunk(4_000, say("carry me home over the hills light the night we sing"), audioPresent = true)
        assertEquals(TrackerState.Locked, s.state)
        assertEquals(22.0, s.tokenPosition, 1e-9) // chorus 2, not 3
        assertEquals(3, s.lineIndex)
    }

    @Test fun `scattered matches do not lock a whole-song search`() {
        val l = LyricsParser.parse(
            """
            i walk the road alone tonight
            and you are there beside me
            we hold the light for you and i
            the river knows the way home
            """.trimIndent()
        )
        val t = LiveTracker(l)
        val s = t.onChunk(2_000, say("i the you"), audioPresent = true)
        assertEquals(TrackerState.Listening, s.state)
        assertEquals(0.0, s.tokenPosition, 1e-9)
        assertEquals(TrackerState.Locked, t.onChunk(4_000, say("the river knows"), audioPresent = true).state)
    }

    @Test fun `short tail re-heard just before the anchor stays in the current repeat`() {
        val t = tracker()
        assertEquals(23.0, t.onChunk(0, say("carry me home over the hills $chorus"), audioPresent = true).tokenPosition, 1e-9)
        // 1 s later the extrapolated position is still on line 4, so line 3 is inside the window.
        val s = t.onChunk(1_000, say("we sing"), audioPresent = true)
        assertTrue(s.tokenPosition <= 23.0, "$s") // not chorus 3 (32)
        assertEquals(22.0, s.tokenPosition, 1e-9) // after "we sing" of chorus 2
        assertEquals(3, s.lineIndex)

        val t2 = tracker()
        assertEquals(22.0, t2.onChunk(0, say("carry me home over the hills light the night we sing"), audioPresent = true).tokenPosition, 1e-9)
        val s2 = t2.onChunk(1_000, say("night we"), audioPresent = true)
        assertEquals(21.0, s2.tokenPosition, 1e-9) // chorus 2, not chorus 3 (31)
        assertEquals(3, s2.lineIndex)

        // Reviewer's timing: 2 s later extrapolation has reached line 5, but the window still reaches back
        // to the anchor's line, so the tail stays in chorus 2.
        val t3 = tracker()
        t3.onChunk(0, say("carry me home over the hills $chorus"), audioPresent = true)
        assertEquals(22.0, t3.onChunk(2_000, say("we sing"), audioPresent = true).tokenPosition, 1e-9)
        val t4 = tracker()
        t4.onChunk(0, say("carry me home over the hills light the night we sing"), audioPresent = true)
        assertEquals(21.0, t4.onChunk(2_000, say("night we"), audioPresent = true).tokenPosition, 1e-9)
    }

    @Test fun `weak windowed match more than one lyric line ahead does not lock`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true) // anchor 5, line 1
        val far = t.onChunk(2_000, say("stones remember"), audioPresent = true) // line 4: 3 lines ahead
        assertEquals(TrackerState.Coasting, far.state)
        val near = t.onChunk(3_000, say("carry me"), audioPresent = true) // line 2: one line past line 1
        assertEquals(TrackerState.Locked, near.state)
        assertEquals(13.0, near.tokenPosition, 1e-9)
        val strong = t.onChunk(4_000, say("stones remember every"), audioPresent = true) // run of 3 is enough
        assertEquals(TrackerState.Locked, strong.state)
        assertEquals(26.0, strong.tokenPosition, 1e-9)
    }

    @Test fun `locks when the singer splits a reduplicated word`() {
        val l = LyricsParser.parse("kupu-kupu terbang tinggi\nsayangku jangan pergi")
        val s = LiveTracker(l).onChunk(2_000, say("kupu kupu terbang tinggi"), audioPresent = true)
        assertEquals(TrackerState.Locked, s.state)
        assertEquals(3.0, s.tokenPosition, 1e-9)
    }

    @Test fun `overlapping windows with line pauses never jump to a later chorus repeat`() {
        // Original lyric: a three-line chorus (with an internal repeat) sung three times.
        val l = LyricsParser.parse(
            """
            walking down the empty street
            counting every stone
            hold me now
            hold me now
            we will rise again
            the morning finds us here
            waiting by the open door
            hold me now
            hold me now
            we will rise again
            hold me now
            hold me now
            we will rise again
            """.trimIndent()
        )
        val repeatStarts = listOf(2, 7, 10)
        fun repeatOf(line: Int): Int = repeatStarts.indexOfLast { it <= line } // -1 before the first chorus
        fun inRepeat(line: Int): Boolean = line in 2..4 || line >= 7

        data class Sung(val text: String, val line: Int, val startMs: Long, val endMs: Long)
        for (gapMs in listOf(800L, 1_500L, 2_500L)) for (dropEvery in listOf(0, 7, 10)) {
            val sung = ArrayList<Sung>()
            var t = 3_000L
            var k = 0
            for (line in l.lines) {
                for (w in line.words) {
                    if (dropEvery == 0 || ++k % dropEvery != 0) sung += Sung(w.display, line.index, t, t + 350)
                    t += 500
                }
                t += gapMs
            }
            val end = t + 4_000
            val tracker = LiveTracker(l)
            var nextWindowEnd = 2_000L
            val latencyMs = 1_000L
            var now = 2_000L
            while (now < end) {
                if (now == nextWindowEnd + latencyMs) {
                    // Each chunk carries the words of the last 4 s of audio, delivered 1 s late.
                    val heard = sung.filter { it.endMs > nextWindowEnd - 4_000 && it.startMs < nextWindowEnd }
                        .map { HeardWord(it.text, it.startMs, it.endMs, 0.9f) }
                    tracker.onChunk(now, heard, audioPresent = true)
                    nextWindowEnd += 2_000
                }
                val trueLine = sung.lastOrNull { it.startMs <= now }?.line
                val shown = tracker.snapshot(now)
                if (trueLine != null && shown.state != TrackerState.Listening && inRepeat(shown.lineIndex)) {
                    // Coasting may lead the singer into the next repeat by at most maxCoastLines lines.
                    val ahead = repeatOf(shown.lineIndex) > repeatOf(trueLine) && shown.lineIndex - trueLine > 2
                    assertTrue(!ahead, "gap=$gapMs drop=$dropEvery t=$now sung line $trueLine, shown $shown")
                }
                now += 100
            }
        }
    }

    @Test fun `user jump overrides position`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true)
        val s = t.onUserJump(3_000, 4)
        assertEquals(TrackerState.Locked, s.state)
        assertEquals(23.0, s.tokenPosition, 1e-9)
        assertEquals(4, s.lineIndex)
        assertEquals(25.0, t.snapshot(4_000).tokenPosition, 1e-9)
    }

    @Test fun `snapshot is pure`() {
        val t = tracker()
        t.onChunk(0, say(opening), audioPresent = true)
        val a = t.snapshot(3_000)
        val b = t.snapshot(3_000)
        assertEquals(a, b)
        t.snapshot(6_000)
        assertEquals(a, t.snapshot(3_000))
        assertEquals(TrackerState.Locked, t.snapshot(1_000).state)
        assertEquals(7.0, t.snapshot(1_000).tokenPosition, 1e-9)
    }

    @Test fun `search window counts non-spacer lines only`() {
        // lines: 0 [0-2], 1 spacer, 2 spacer, 3 [3-5], 4 [6-8], 5 spacer, 6 [9-11], 7 [12-14]
        val l = LyricsParser.parse(
            "hills hold dawn\n\n[Chorus]\nrivers run wide\nlanterns burn low\n\nwe walk home\nunder silver skies"
        )
        assertEquals(15, l.tokens.size)
        val t = LiveTracker(l)
        assertEquals(0 to 15, t.searchWindow(3)) // one lyric line back is line 0, across two spacers
        assertEquals(0 to 15, t.searchWindow(0)) // only 3 lyric lines follow, fewer than 4 → size
        assertEquals(3 to 15, t.searchWindow(4))
        val narrow = LiveTracker(l, TrackerConfig(windowLinesAhead = 1))
        assertEquals(0 to 9, narrow.searchWindow(3)) // 2nd lyric line after 3 is line 6
        assertEquals(3 to 12, narrow.searchWindow(4)) // 2nd lyric line after 4 is line 7
        assertEquals(6 to 15, narrow.searchWindow(6)) // back over spacer 5 to line 4; nothing after line 7 → size
        assertEquals(0 to 6, narrow.searchWindow(0)) // 2nd lyric line after 0 is line 4
    }

    @Test fun `double-spaced lyrics get the same window and coast cap as single-spaced`() {
        val rows = listOf(
            "amber fields wake slow", "copper bells ring out", "salt winds cross the bay",
            "paper boats sail far", "ember skies fade soft", "willow shade runs long", "harbor lamps glow dim",
        )
        val single = LyricsParser.parse(rows.joinToString("\n"))
        val double = LyricsParser.parse(rows.joinToString("\n\n"))
        assertEquals(single.tokens.map { it.token }, double.tokens.map { it.token })
        val ts = LiveTracker(single)
        val td = LiveTracker(double)
        for (k in rows.indices) assertEquals(ts.searchWindow(k), td.searchWindow(2 * k), "lyric line $k")

        val s1 = ts.onChunk(0, say(rows[1]), audioPresent = true)
        val d1 = td.onChunk(0, say(rows[1]), audioPresent = true)
        assertEquals(s1.tokenPosition, d1.tokenPosition, 1e-9)
        var s = s1
        var d = d1
        for (now in 2_000L..9_000L step 1_000) {
            s = ts.onChunk(now, emptyList(), audioPresent = true)
            d = td.onChunk(now, emptyList(), audioPresent = true)
        }
        assertEquals(TrackerState.Coasting, d.state)
        assertEquals(s.tokenPosition, d.tokenPosition, 1e-9) // capped at the same token
        assertEquals(3, s.lineIndex) // last lock on line 1, at most 2 lyric lines further
        assertEquals(6, d.lineIndex) // same lyric line in the double-spaced layout
        assertTrue(d.tokenPosition < 17.0, "pos ${d.tokenPosition}") // lyric line 4 starts at token 17
    }

    @Test fun `zero-token lyrics do not crash`() {
        for (l in listOf(Lyrics(emptyList()), LyricsParser.parse("[Instrumental]\n\n(guitar solo)"))) {
            val t = LiveTracker(l)
            val s = t.onChunk(2_000, say(opening), audioPresent = true)
            assertEquals(TrackerState.Listening, s.state)
            assertEquals(0, s.lineIndex)
            assertEquals(0.0, s.tokenPosition, 1e-9)
            assertEquals(0, t.onUserJump(3_000, 0).lineIndex)
            assertEquals(0, t.snapshot(9_000).lineIndex)
            assertEquals(0.0, t.snapshot(9_000).tokenPosition, 1e-9)
        }
    }
}
