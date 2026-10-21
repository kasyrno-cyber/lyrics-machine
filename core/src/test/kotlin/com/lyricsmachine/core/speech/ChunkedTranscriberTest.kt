package com.lyricsmachine.core.speech

import com.lyricsmachine.core.Lang
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedTranscriberTest {
    private class FakeEngine(val onCall: suspend (Int) -> Unit = {}) : SpeechEngine {
        var calls = 0
        val languages = mutableListOf<Lang?>()
        override suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord> {
            val n = calls++
            languages += language
            onCall(n)
            return listOf(HeardWord("w$n", 0, 100, 0.9f))
        }
    }

    private val chunk = 480_000 // 30 s at 16 kHz

    @Test fun `chunked transcriber offsets second chunk by 30s`() = runTest {
        val engine = FakeEngine()
        val out = ChunkedTranscriber(engine).transcribe(
            sequenceOf(FloatArray(chunk), FloatArray(chunk)), 2L * chunk, Lang.EN
        ) {}
        assertEquals(listOf(0L, 30_000L), out.map { it.startMs })
        assertEquals(listOf(100L, 30_100L), out.map { it.endMs })
        assertEquals(listOf<Lang?>(Lang.EN, Lang.EN), engine.languages)
    }

    @Test fun `short last chunk uses actual lengths`() = runTest {
        val out = ChunkedTranscriber(FakeEngine()).transcribe(
            sequenceOf(FloatArray(chunk), FloatArray(16_000), FloatArray(16_000)), chunk + 32_000L, null
        ) {}
        assertEquals(listOf(0L, 30_000L, 31_000L), out.map { it.startMs })
    }

    @Test fun `progress reaches 1`() = runTest {
        val p = mutableListOf<Float>()
        ChunkedTranscriber(FakeEngine()).transcribe(
            sequenceOf(FloatArray(chunk), FloatArray(chunk / 2)), chunk * 3L / 2, null
        ) { p += it }
        assertEquals(listOf(2f / 3f, 1f), p)
        assertEquals(1f, p.last())
    }

    @Test fun `progress is clamped and zero total reports 1 at end`() = runTest {
        val p = mutableListOf<Float>()
        ChunkedTranscriber(FakeEngine()).transcribe(sequenceOf(FloatArray(100)), 50, null) { p += it }
        assertEquals(listOf(1f), p)
        p.clear()
        ChunkedTranscriber(FakeEngine()).transcribe(sequenceOf(FloatArray(100), FloatArray(100)), 0, null) { p += it }
        assertEquals(listOf(1f), p)
    }

    @Test fun `empty sequence reports 1`() = runTest {
        val p = mutableListOf<Float>()
        val out = ChunkedTranscriber(FakeEngine()).transcribe(emptySequence(), 1000, null) { p += it }
        assertTrue(out.isEmpty())
        assertEquals(listOf(1f), p)
    }

    @Test fun `cancellation stops between chunks`() = runTest {
        var job: Job? = null
        // Non-cooperative engine (like a JNI call): cancels during call 0, never suspends.
        val engine = FakeEngine { if (it == 0) job!!.cancel() }
        var failure: Throwable? = null
        job = launch {
            try {
                ChunkedTranscriber(engine).transcribe(
                    generateSequence { FloatArray(10) }.take(5), 50, null
                ) {}
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(failure is CancellationException)
        assertEquals(1, engine.calls)
    }

    @Test fun `cancellation during the final chunk never reports completion`() = runTest {
        // totalSamples 0: no per-chunk progress, only the final 1f; exact total: the last chunk's progress is 1f.
        for (total in listOf(0L, 30L)) {
            var job: Job? = null
            val engine = FakeEngine { if (it == 2) job!!.cancel() } // non-cooperative, like a JNI call
            val progress = mutableListOf<Float>()
            var failure: Throwable? = null
            job = launch {
                try {
                    ChunkedTranscriber(engine).transcribe(generateSequence { FloatArray(10) }.take(3), total, null) {
                        progress += it
                    }
                } catch (e: Throwable) {
                    failure = e
                    throw e
                }
            }
            job.join()
            assertTrue(failure is CancellationException, "total=$total failure=$failure")
            assertEquals(3, engine.calls)
            assertFalse(1f in progress, "total=$total progress=$progress")
        }
    }
}
