package com.lyricsmachine.app.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.align.Alignment
import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongRepositoryTest {
    private lateinit var db: AppDatabase
    private var readable = true
    private lateinit var repo: SongRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SongRepository(db, UnconfinedTestDispatcher()) { readable }
    }

    @After fun tearDown() { db.close() }

    private fun song(title: String = "Quiet Harbor", audio: String? = "content://x/1", lyrics: String = "blue river walks\nover quiet stones") =
        SongEntity(title = title, artist = "Test Artist", language = Lang.EN, lyricsText = lyrics, audioUri = audio, createdAt = 1L)

    private val alignment = Alignment(
        lines = listOf(LineTiming(0, 1000, 3000), LineTiming(1, 3000, 5000)),
        words = listOf(
            WordTiming(0, 0, 1000, 2000, true), WordTiming(0, 1, 2000, 3000, false),
            WordTiming(1, 0, 3000, 4000, true),
        ),
        matchRatio = 0.8,
    )

    @Test fun `insert then observe shows song`() = runBlocking {
        val id = repo.upsert(song())
        val list = repo.observeLibrary().first()
        assertEquals(listOf(SongSummary(id, "Quiet Harbor", "Test Artist", SongMode.AUDIO_NOT_ANALYSED)), list)
    }

    @Test fun `library is ordered by title ignoring case`() = runBlocking {
        repo.upsert(song(title = "banana"))
        repo.upsert(song(title = "Apple"))
        repo.upsert(song(title = "cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), repo.observeLibrary().first().map { it.title })
    }

    @Test fun `save and load alignment round trips`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        val (lines, words) = repo.loadTimings(id)
        assertEquals(alignment.lines, lines)
        assertEquals(alignment.words, words)
        assertEquals(0.8, repo.analysis(id)!!.matchRatio, 1e-9)
        assertEquals("base", repo.analysis(id)!!.modelName)
        assertEquals(SongMode.AUDIO_READY, repo.observeLibrary().first().single().mode)
    }

    @Test fun `saving again replaces existing timings`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        repo.saveAlignment(id, Alignment(listOf(LineTiming(0, 10, 20)), emptyList(), 0.5), "small")
        val (lines, words) = repo.loadTimings(id)
        assertEquals(listOf(LineTiming(0, 10, 20)), lines)
        assertTrue(words.isEmpty())
        assertEquals("small", repo.analysis(id)!!.modelName)
    }

    @Test fun `empty alignment saves only the analysis row`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, Alignment(emptyList(), emptyList(), 0.0), "base")
        assertNotNull(repo.analysis(id))
        assertTrue(repo.loadTimings(id).first.isEmpty())
        assertEquals(SongMode.AUDIO_NOT_ANALYSED, repo.observeLibrary().first().single().mode)
    }

    @Test fun `editing lyrics clears timings`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        assertEquals(5, db.songDao().timings(id).size)
        repo.upsert(repo.get(id)!!.copy(lyricsText = "a brand new line"))
        assertTrue(db.songDao().timings(id).isEmpty())
        assertTrue(repo.loadTimings(id).first.isEmpty())
        assertNull(repo.analysis(id))
        assertEquals(SongMode.AUDIO_NOT_ANALYSED, repo.observeLibrary().first().single().mode)
    }

    @Test fun `changing audio clears timings but renaming keeps them`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        repo.upsert(repo.get(id)!!.copy(title = "Renamed"))
        assertEquals(2, repo.loadTimings(id).first.size)
        assertEquals(5, db.songDao().timings(id).size)
        repo.upsert(repo.get(id)!!.copy(audioUri = "content://x/2"))
        assertTrue(db.songDao().timings(id).isEmpty())
        assertTrue(repo.loadTimings(id).first.isEmpty())
        assertNull(repo.analysis(id))
    }

    @Test fun `song with unreadable audio uri is reported as lyrics only`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        readable = false
        assertEquals(SongMode.LYRICS_ONLY, repo.observeLibrary().first().single().mode)
        readable = true
        assertEquals(SongMode.AUDIO_READY, repo.observeLibrary().first().single().mode)
        repo.upsert(song(title = "No Audio", audio = null))
        assertEquals(SongMode.LYRICS_ONLY, repo.observeLibrary().first().first { it.title == "No Audio" }.mode)
    }

    @Test fun `lrc timings are the fallback and count as audio ready`() = runBlocking {
        val id = repo.upsert(song(lyrics = "[00:01.00]blue river walks\n[00:05.00]over quiet stones"))
        val (lines, words) = repo.loadTimings(id)
        assertEquals(listOf(LineTiming(0, 1000, 5000), LineTiming(1, 5000, 9000)), lines)
        assertTrue(words.isEmpty())
        assertEquals(SongMode.AUDIO_READY, repo.observeLibrary().first().single().mode)
        readable = false
        assertEquals(SongMode.LYRICS_ONLY, repo.observeLibrary().first().single().mode)
    }

    @Test fun `delete removes timings`() = runBlocking {
        val id = repo.upsert(song())
        repo.saveAlignment(id, alignment, "base")
        assertEquals(5, db.songDao().timings(id).size)
        repo.delete(id)
        assertTrue(db.songDao().timings(id).isEmpty())
        assertNull(repo.get(id))
        assertNull(repo.analysis(id))
        assertTrue(repo.loadTimings(id).first.isEmpty())
        assertTrue(repo.observeLibrary().first().isEmpty())
    }

    @Test fun `analysis with zero lines still falls back to lrc timings`() = runBlocking {
        val id = repo.upsert(song(lyrics = "[00:01.00]blue river walks\n[00:05.00]over quiet stones"))
        repo.saveAlignment(id, Alignment(emptyList(), emptyList(), 0.0), "base")
        assertNotNull(repo.analysis(id))
        assertEquals(listOf(LineTiming(0, 1000, 5000), LineTiming(1, 5000, 9000)), repo.loadTimings(id).first)
        assertEquals(SongMode.AUDIO_READY, repo.observeLibrary().first().single().mode)
    }

    @Test fun `library flow re-emits across upsert, saveAlignment and delete`() = runBlocking {
        val seen = mutableListOf<SongMode?>()
        val job = launch(Dispatchers.Default) {
            repo.observeLibrary().map { it.singleOrNull()?.mode }.distinctUntilChanged().toList(seen)
        }
        suspend fun awaitLast(expected: SongMode?) = withTimeout(10_000) {
            while (synchronized(seen) { seen.lastOrNull() } != expected || seen.isEmpty()) delay(20)
        }
        awaitLast(null)
        val id = repo.upsert(song())
        awaitLast(SongMode.AUDIO_NOT_ANALYSED)
        repo.saveAlignment(id, alignment, "base")
        awaitLast(SongMode.AUDIO_READY)
        repo.delete(id)
        awaitLast(null)
        job.cancel()
        val got = seen.toList()
        // The songs and timings tables invalidate separately, so delete may transiently show AUDIO_NOT_ANALYSED
        // (timings gone, song row not yet) before the song disappears.
        assertEquals(listOf(null, SongMode.AUDIO_NOT_ANALYSED, SongMode.AUDIO_READY), got.take(3))
        assertNull(got.last())
        assertTrue(got.drop(3).dropLast(1).all { it == SongMode.AUDIO_NOT_ANALYSED })
    }
}
