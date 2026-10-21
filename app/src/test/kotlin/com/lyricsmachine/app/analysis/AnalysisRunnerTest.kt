package com.lyricsmachine.app.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.speech.HeardWord
import com.lyricsmachine.core.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisRunnerTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SongRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SongRepository(db, UnconfinedTestDispatcher()) { true }
    }

    @After fun tearDown() { db.close() }

    private val lyrics = "blue river walks\nover quiet stones"

    private suspend fun addSong(audio: String? = "content://x/1"): Long =
        repo.upsert(SongEntity(title = "Quiet Harbor", artist = "A", language = Lang.EN, lyricsText = lyrics, audioUri = audio, createdAt = 1L))

    /** Returns [words] for the first chunk and nothing afterwards; records the language it was given. */
    private class FakeEngine(val words: List<HeardWord>, val onCall: suspend (Int) -> Unit = {}) : SpeechEngine {
        var calls = 0
        var lastLang: Lang? = Lang.MIXED
        override suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord> {
            lastLang = language
            onCall(calls)
            return if (calls++ == 0) words else emptyList()
        }
    }

    private fun heard(vararg w: String) = w.mapIndexed { i, t -> HeardWord(t, i * 500L, i * 500L + 400, 0.9f) }

    private val chunk = FloatArray(16_000)
    private fun chunks(n: Int) = generateSequence { chunk }.take(n)

    private fun runner(
        engine: SpeechEngine?,
        model: String = "ggml-base-q5_1.bin",
        decode: (String) -> Sequence<FloatArray> = { chunks(2) },
    ) = AnalysisRunner(
        repo = repo,
        engineProvider = { engine?.let { EngineChoice(it, model) } },
        decode = decode,
        totalSamples = { 32_000L },
    )

    @Test fun `run saves alignment and reports ratio`() = runBlocking {
        val id = addSong()
        val engine = FakeEngine(heard("blue", "river", "walks", "over", "quiet", "stones"))
        val progress = ArrayList<Float>()
        val result = runner(engine).run(id) { progress += it }
        assertTrue(result is AnalysisResult.Done)
        result as AnalysisResult.Done
        assertEquals(1.0, result.matchRatio, 1e-9)
        assertEquals(false, result.lowQuality)
        assertEquals(1f, progress.last(), 0f)
        assertEquals("ggml-base-q5_1.bin", repo.analysis(id)!!.modelName)
        assertEquals(2, repo.loadTimings(id).first.size)
        assertEquals(Lang.EN, engine.lastLang)
    }

    @Test fun `mixed language is sent to the engine as auto-detect`() = runBlocking {
        val id = repo.upsert(SongEntity(title = "t", artist = "a", language = Lang.MIXED, lyricsText = lyrics, audioUri = "content://x", createdAt = 1L))
        val engine = FakeEngine(heard("blue"))
        runner(engine).run(id) {}
        assertNull(engine.lastLang)
    }

    @Test fun `low match ratio reported as lowQuality`() = runBlocking {
        val id = addSong()
        val result = runner(FakeEngine(heard("zebra", "yellow", "quartz", "plum"))).run(id) {}
        result as AnalysisResult.Done
        assertTrue(result.lowQuality)
        assertNotNull(repo.analysis(id))
    }

    @Test fun `unreadable audio returns AUDIO_UNREADABLE and keeps song`() = runBlocking {
        val id = addSong()
        for (failure in listOf<() -> Throwable>({ IOException("x") }, { SecurityException("x") }, { IllegalArgumentException("x") }, { IllegalStateException("x") })) {
            // Failure thrown while opening the decoder.
            val r1 = runner(FakeEngine(heard("blue")), decode = { throw failure() }).run(id) {}
            assertEquals(AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE), r1)
            // Failure thrown mid-iteration.
            val r2 = runner(FakeEngine(heard("blue")), decode = { sequence { yield(chunk); throw failure() } }).run(id) {}
            assertEquals(AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE), r2)
        }
        assertNotNull(repo.get(id))
        assertNull(repo.analysis(id))
    }

    @Test fun `missing song or missing audio uri is AUDIO_UNREADABLE`() = runBlocking {
        assertEquals(AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE), runner(FakeEngine(heard("a"))).run(999) {})
        val id = addSong(audio = null)
        assertEquals(AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE), runner(FakeEngine(heard("a"))).run(id) {})
    }

    @Test fun `no model returns MODEL_MISSING`() = runBlocking {
        val id = addSong()
        assertEquals(AnalysisResult.Error(AnalysisError.MODEL_MISSING), runner(null).run(id) {})
        assertNull(repo.analysis(id))
    }

    @Test fun `engine exception returns ENGINE_FAILED and saves nothing`() = runBlocking {
        val id = addSong()
        val engine = FakeEngine(emptyList(), onCall = { throw IllegalStateException("boom") })
        assertEquals(AnalysisResult.Error(AnalysisError.ENGINE_FAILED), runner(engine).run(id) {})
        assertNull(repo.analysis(id))
    }

    @Test fun `cancellation discards partial results`() = runBlocking {
        val id = addSong()
        val inSecondChunk = CompletableDeferred<Unit>()
        val engine = FakeEngine(heard("blue", "river", "walks")) { call ->
            if (call == 1) { inSecondChunk.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        val job = async(Dispatchers.Default) { runner(engine).run(id) {} }
        inSecondChunk.await()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertNull(repo.analysis(id))
        assertTrue(repo.loadTimings(id).second.isEmpty())
        assertNotNull(repo.get(id))
    }

    @Test fun `decoder is closed after run, also on cancel path`() = runBlocking {
        val id = addSong()
        var closed = 0
        val decode: (String) -> Sequence<FloatArray> = {
            object : Sequence<FloatArray>, AutoCloseable {
                override fun iterator() = chunks(2).iterator()
                override fun close() { closed++ }
            }
        }
        runner(FakeEngine(heard("blue")), decode = decode).run(id) {}
        assertEquals(1, closed)
    }
}
