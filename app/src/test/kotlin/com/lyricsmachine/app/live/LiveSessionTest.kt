package com.lyricsmachine.app.live

import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.HeardWord
import com.lyricsmachine.core.speech.SpeechEngine
import com.lyricsmachine.core.tracker.TrackerState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable

/** Plain JVM: LiveSession with a fake mic, fake engines and virtual time. */
class LiveSessionTest {
    // Original text. Token ranges: line0 0-5, line1 6-12, line2 13-18, line3 19-25.
    private val song = LyricsParser.parse(
        """
        paper boats sail past the harbour
        we wave our hands beneath the lamps
        morning comes with gulls and thunder
        we wave our hands beneath the lamps
        """.trimIndent()
    )
    private val opening = "paper boats sail past the harbour"

    private class FakeMic : MicSource {
        var onSamples: ((FloatArray) -> Unit)? = null
        var started = 0
        var stopped = 0
        override fun start(onSamples: (FloatArray) -> Unit) { started++; this.onSamples = onSamples }
        override fun stop() { stopped++ }
        fun emit(samples: FloatArray) = onSamples!!.invoke(samples)
    }

    /** Shared counters so concurrency is measured across engines (base and tiny). */
    private class Stats { var active = 0; var maxActive = 0; var calls = 0 }

    private class FakeEngine(
        val spec: ModelSpec,
        private val stats: Stats,
        private val delayMs: Long,
        private val words: String,
        private val nonCancellable: Boolean = false,
    ) : SpeechEngine, Closeable {
        var calls = 0
        var closed = 0
        var closedWhileActive = false
        val languages = mutableListOf<Lang?>()
        private var active = 0

        override suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord> {
            check(closed == 0) { "transcribe on closed engine" }
            calls++; stats.calls++
            languages += language
            active++; stats.active++
            stats.maxActive = maxOf(stats.maxActive, stats.active)
            try {
                if (nonCancellable) withContext(NonCancellable) { delay(delayMs) } else delay(delayMs)
            } finally {
                active--; stats.active--
            }
            return words.split(" ").mapIndexed { i, w -> HeardWord(w, i * 300L, i * 300L + 250, 0.9f) }
        }

        override fun close() {
            if (active > 0) closedWhileActive = true
            closed++
        }
    }

    private inner class Harness(
        val scope: TestScope,
        baseDelay: Long = 400,
        tinyDelay: Long = 400,
        words: String = opening,
        language: Lang? = Lang.EN,
        nonCancellable: Boolean = false,
        initialModel: ModelSpec = ModelSpec.BASE,
    ) {
        val stats = Stats()
        val mic = FakeMic()
        val requested = mutableListOf<ModelSpec>()
        val base = FakeEngine(ModelSpec.BASE, stats, baseDelay, words, nonCancellable)
        val tiny = FakeEngine(ModelSpec.TINY, stats, tinyDelay, words, nonCancellable)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val session = LiveSession(
            lyrics = song,
            mic = mic,
            engineFor = { spec -> requested += spec; if (spec == ModelSpec.BASE) base else tiny },
            language = language,
            clock = { scope.currentTime },
            scope = scope.backgroundScope,
            trackerDispatcher = dispatcher,
            initialModel = initialModel,
        )
        val events = mutableListOf<LiveEvent>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                session.events.toList(events)
            }
        }

        fun loud() { mic.emit(FloatArray(16_000) { if (it % 2 == 0) 0.5f else -0.5f }); scope.runCurrent() }
        fun quiet() { mic.emit(FloatArray(16_000)); scope.runCurrent() }
        fun advanceTo(ms: Long) { scope.advanceTimeBy(ms - scope.currentTime); scope.runCurrent() }
    }

    @Test fun `locks after mic hears phrase`() = runTest {
        val h = Harness(this)
        h.session.start()
        h.loud()
        h.advanceTo(2_401) // first chunk at 2000, transcription takes 400 ms
        assertEquals(1, h.base.calls)
        assertEquals(TrackerState.Locked, h.session.snapshot.value.state)
        assertEquals(1, h.session.snapshot.value.lineIndex)
        h.session.stop()
    }

    @Test fun `skips transcription on silence`() = runTest {
        val h = Harness(this)
        h.session.start()
        h.quiet()
        h.advanceTo(6_001) // chunks at 2000, 4000, 6000, all silent
        assertEquals(0, h.stats.calls)
        // onChunk(audioPresent = false) was still fed: silence for >= silencePauseMs pauses the tracker.
        assertEquals(TrackerState.Paused, h.session.snapshot.value.state)
        h.session.stop()
    }

    @Test fun `slow engine switches to tiny model`() = runTest {
        val h = Harness(this, baseDelay = 2_500, tinyDelay = 300)
        h.session.start()
        h.loud()
        // Base runs at 2000, 6000, 10000 (ticks while busy are skipped); the third ends at 12500.
        h.advanceTo(12_501)
        assertEquals(3, h.base.calls)
        assertEquals(listOf(LiveEvent.SwitchedToTinyModel), h.events)
        assertEquals(listOf(ModelSpec.BASE, ModelSpec.TINY), h.requested)
        assertEquals(1, h.base.closed)
        assertFalse(h.base.closedWhileActive)
        h.advanceTo(14_301)
        assertEquals(1, h.tiny.calls)
        assertEquals(3, h.base.calls)
        h.session.stop()
    }

    @Test fun `still too slow emitted once`() = runTest {
        val h = Harness(this, baseDelay = 2_500, tinyDelay = 2_500)
        h.session.start()
        h.loud()
        h.advanceTo(40_000)
        assertTrue(h.tiny.calls > 3)
        assertEquals(listOf(LiveEvent.SwitchedToTinyModel, LiveEvent.StillTooSlow), h.events)
        h.session.stop()
    }

    @Test fun `still too slow when starting on tiny`() = runTest {
        val h = Harness(this, tinyDelay = 2_500, initialModel = ModelSpec.TINY)
        h.session.start()
        h.loud()
        h.advanceTo(20_000)
        assertEquals(listOf(ModelSpec.TINY), h.requested)
        assertEquals(listOf(LiveEvent.StillTooSlow), h.events)
        h.session.stop()
    }

    @Test fun `jumpTo moves snapshot line`() = runTest {
        val h = Harness(this)
        h.session.start()
        h.session.jumpTo(3)
        runCurrent()
        assertEquals(3, h.session.snapshot.value.lineIndex)
        assertEquals(TrackerState.Locked, h.session.snapshot.value.state)
        h.session.stop()
    }

    @Test fun `no concurrent transcriptions`() = runTest {
        val h = Harness(this, baseDelay = 3_000, tinyDelay = 3_000)
        h.session.start()
        h.loud()
        h.advanceTo(20_000)
        assertEquals(1, h.stats.maxActive)
        // Each call spans the next tick, so only every other tick (2000, 6000, 10000, 14000, 18000) transcribes.
        assertEquals(5, h.stats.calls)
        h.session.stop()
    }

    @Test fun `onChunk timestamp is the window end not the inference end`() = runTest {
        val h = Harness(this, baseDelay = 450)
        h.session.start()
        h.loud()
        h.advanceTo(2_451) // window taken at 2000, result applied at 2450
        val s = h.session.snapshot.value
        assertEquals(TrackerState.Locked, s.state)
        // Lock at token 6 stamped 2000; at the initial pace of 2 tokens/s, 2450 extrapolates 0.9 tokens on.
        assertEquals(6.9, s.tokenPosition, 1e-6)
        h.session.stop()
    }

    @Test fun `snapshot ticker advances between chunks`() = runTest {
        val h = Harness(this, baseDelay = 100)
        h.session.start()
        h.loud()
        h.advanceTo(2_101)
        val first = h.session.snapshot.value.tokenPosition
        h.advanceTo(2_801) // no chunk lands between 2100 and 4000; only the ticker publishes
        val later = h.session.snapshot.value.tokenPosition
        assertEquals(first + 1.4, later, 1e-6)
        h.session.stop()
    }

    @Test fun `stop closes the engine and the mic and is idempotent`() = runTest {
        val h = Harness(this)
        h.session.start()
        assertEquals(1, h.mic.started)
        h.session.stop()
        h.session.stop()
        runCurrent()
        assertEquals(1, h.mic.stopped)
        assertEquals(1, h.base.closed)
    }

    @Test fun `stop during a transcription closes the engine after it completes`() = runTest {
        val h = Harness(this, baseDelay = 1_000, nonCancellable = true)
        h.session.start()
        h.loud()
        h.advanceTo(2_500) // transcription in flight until 3000
        h.session.stop()
        runCurrent()
        assertEquals(0, h.base.closed)
        h.advanceTo(3_001)
        assertEquals(1, h.base.closed)
        assertFalse(h.base.closedWhileActive)
    }

    @Test fun `ticker stops after stop`() = runTest {
        val h = Harness(this, baseDelay = 100)
        h.session.start()
        h.loud()
        h.advanceTo(2_101)
        h.session.stop()
        val frozen = h.session.snapshot.value
        h.advanceTo(5_000)
        assertEquals(frozen, h.session.snapshot.value)
    }

    @Test fun `start after stop throws`() = runTest {
        val h = Harness(this)
        h.session.start()
        h.session.stop()
        try {
            h.session.start()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun `mixed language is passed to the engine as auto-detect`() = runTest {
        val h = Harness(this, language = Lang.MIXED)
        h.session.start()
        h.loud()
        h.advanceTo(2_401)
        assertEquals(1, h.base.languages.size)
        assertNull(h.base.languages.single())
        h.session.stop()
    }
}
