package com.lyricsmachine.app.ui.edit

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.align.Alignment
import com.lyricsmachine.core.align.LineTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditSongViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SongRepository
    private val enqueued = mutableListOf<Long>()
    private val persisted = mutableListOf<Uri>()
    private var importText = ""

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SongRepository(db, UnconfinedTestDispatcher()) { true }
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val released = mutableListOf<Uri>()
    private var grantFails = false
    private var enqueueFails = false

    private fun vm(songId: Long = -1L, repository: SongRepository = repo) = EditSongViewModel(
        songId = songId,
        repository = repository,
        readText = { importText },
        enqueueAnalysis = { if (enqueueFails) error("boom"); enqueued += it },
        takePersistablePermission = { if (grantFails) throw SecurityException("no grant"); persisted += it },
        releasePersistablePermission = { released += it },
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private suspend fun EditSongViewModel.loaded() = state.first { !it.loading }

    private suspend fun seed(audio: String? = "content://media/audio/1", lyrics: String = "blue river walks", analysed: Boolean = true): Long {
        val id = repo.upsert(
            SongEntity(title = "Old", artist = "A", language = Lang.EN, lyricsText = lyrics, audioUri = audio, createdAt = 1L),
        )
        if (analysed) repo.saveAlignment(id, Alignment(listOf(LineTiming(0, 0, 1000)), emptyList(), 0.9), "tiny")
        return id
    }

    private fun EditSongViewModel.fill(title: String = "Quiet Harbor", lyrics: String = "blue river walks\nover quiet stones") {
        onTitleChange(title)
        onLyricsChange(lyrics)
    }

    @Test fun `empty lyrics rejected`() = runBlocking {
        val v = vm()
        v.fill(lyrics = "   \n  ")
        assertEquals(SaveOutcome.INVALID_EMPTY_LYRICS, v.save())
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `lyrics with zero tokens rejected`() = runBlocking {
        val v = vm()
        v.fill(lyrics = "[Chorus]\n...")
        assertEquals(SaveOutcome.INVALID_EMPTY_LYRICS, v.save())
    }

    @Test fun `empty title rejected`() = runBlocking {
        val v = vm()
        v.fill(title = "  ")
        assertEquals(SaveOutcome.INVALID_EMPTY_TITLE, v.save())
    }

    @Test fun `blank title is checked before lyrics`() = runBlocking {
        val v = vm()
        v.fill(title = "", lyrics = "")
        assertEquals(SaveOutcome.INVALID_EMPTY_TITLE, v.save())
    }

    @Test fun `lyrics only save`() = runBlocking {
        val v = vm()
        v.fill()
        assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
        assertTrue(enqueued.isEmpty())
        val saved = repo.observeLibrary().first()
        assertEquals(1, saved.size)
    }

    @Test fun `save with audio needs analysis`() = runBlocking {
        val v = vm()
        v.fill()
        v.onAudioPicked(Uri.parse("content://media/audio/1"), "take1.mp3").join()
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(1, enqueued.size)
        assertEquals(1, persisted.size)
        val song = repo.get(enqueued.single())
        assertEquals("content://media/audio/1", song?.audioUri)
    }

    @Test fun `imported lrc fills lyrics field`() = runBlocking {
        importText = "﻿[00:01.00]blue river walks\n[00:05.00]over quiet stones"
        val v = vm()
        v.importFile(Uri.parse("content://docs/1"), "demo.lrc").join()
        assertEquals("[00:01.00]blue river walks\n[00:05.00]over quiet stones", v.state.value.lyrics)
        assertNull(v.state.value.error)
    }

    @Test fun `oversize import shows error`() = runBlocking {
        importText = "la ".repeat(300_000)
        val v = vm()
        v.importFile(Uri.parse("content://docs/2"), "big.txt").join()
        assertEquals("This file is too large to be lyrics.", v.state.value.error)
        assertEquals("", v.state.value.lyrics)
    }

    @Test fun `rename only of analysed song does not re-analyse`() = runBlocking {
        val id = seed()
        val v = vm(id)
        v.loaded()
        v.onTitleChange("New name")
        assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
        assertTrue(enqueued.isEmpty())
        assertNotNull(repo.analysis(id))
        assertEquals("New name", repo.get(id)?.title)
    }

    @Test fun `editing lyrics of analysed song re-analyses`() = runBlocking {
        val id = seed()
        val v = vm(id)
        v.loaded()
        v.onLyricsChange("blue river runs")
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(listOf(id), enqueued)
    }

    @Test fun `renaming an lrc timed song with audio and no analysis does not re-analyse`() = runBlocking {
        val id = seed(lyrics = "[00:01.00]blue river walks\n[00:05.00]over quiet stones", analysed = false)
        val v = vm(id)
        v.loaded()
        v.onTitleChange("Renamed")
        assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `new lrc song with audio needs analysis`() = runBlocking {
        val v = vm()
        v.fill(lyrics = "[00:01.00]blue river walks\n[00:05.00]over quiet stones")
        v.onAudioPicked(Uri.parse("content://media/audio/9"), "x.mp3").join()
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
    }

    @Test fun `remove audio then save is lyrics only and releases the uri`() = runBlocking {
        val id = seed()
        val v = vm(id)
        v.loaded()
        v.removeAudio().join()
        assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
        assertNull(repo.get(id)?.audioUri)
        assertEquals(listOf("content://media/audio/1"), released.map { it.toString() })
    }

    @Test fun `replacing audio releases the old uri on save`() = runBlocking {
        val id = seed()
        val v = vm(id)
        v.loaded()
        v.onAudioPicked(Uri.parse("content://media/audio/2"), "b.mp3").join()
        assertTrue(released.isEmpty())
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(listOf("content://media/audio/1"), released.map { it.toString() })
    }

    @Test fun `old uri shared by another song is not released`() = runBlocking {
        val id = seed()
        seed(analysed = false)
        val v = vm(id)
        v.loaded()
        v.removeAudio().join()
        v.save()
        assertTrue(released.isEmpty())
    }

    @Test fun `abandoned pick is released on discard`() = runBlocking {
        val v = vm()
        v.fill()
        v.onAudioPicked(Uri.parse("content://media/audio/5"), "c.mp3").join()
        v.discardChanges().join()
        assertEquals(listOf("content://media/audio/5"), released.map { it.toString() })
    }

    @Test fun `replacing an unsaved pick releases it immediately`() = runBlocking {
        val v = vm()
        v.fill()
        v.onAudioPicked(Uri.parse("content://media/audio/5"), "c.mp3").join()
        v.onAudioPicked(Uri.parse("content://media/audio/6"), "d.mp3").join()
        assertEquals(listOf("content://media/audio/5"), released.map { it.toString() })
    }

    @Test fun `grant failure shows error and does not attach audio`() = runBlocking {
        grantFails = true
        val v = vm()
        v.onAudioPicked(Uri.parse("content://media/audio/1"), "a.mp3").join()
        assertEquals("Couldn't keep access to this audio file.", v.state.value.error)
        assertNull(v.state.value.audioUri)
    }

    @Test fun `save before load completes saves the loaded data`() = runBlocking {
        val id = seed(audio = null, analysed = false)
        val mainThread = java.util.concurrent.Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainThread.asCoroutineDispatcher())
        try {
            val v = vm(id)
            assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
            assertEquals("Old", repo.get(id)?.title)
            assertEquals("blue river walks", repo.get(id)?.lyricsText)
        } finally {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            mainThread.shutdown()
        }
    }

    @Test fun `form is loading until the song arrives`() = runBlocking {
        val id = seed(analysed = false)
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        try {
            val v = vm(id)
            assertTrue(v.state.value.loading)
            kotlinx.coroutines.withTimeout(10_000) {
                while (v.state.value.loading) { scheduler.advanceUntilIdle(); kotlinx.coroutines.delay(10) }
            }
            assertEquals("Old", v.state.value.title)
            assertTrue(!v.state.value.dirty)
        } finally {
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }
    }

    @Test fun `load failure sets error and never crashes`() = runBlocking {
        val id = seed()
        db.close()
        val v = vm(id)
        val s = v.state.first { !it.loading }
        assertEquals("Couldn't open this song.", s.error)
        assertTrue(s.loadFailed)
        assertEquals(SaveOutcome.INVALID_EMPTY_TITLE, v.save())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @Test fun `dirty then reverted is clean`() = runBlocking {
        val v = vm()
        assertTrue(!v.state.value.dirty)
        v.onTitleChange("x")
        assertTrue(v.state.value.dirty)
        v.onTitleChange("")
        assertTrue(!v.state.value.dirty)
    }

    @Test fun `double save inserts one row and enqueues once`() = runBlocking {
        val v = vm()
        v.fill()
        v.onAudioPicked(Uri.parse("content://media/audio/1"), "a.mp3").join()
        val a = async { v.save() }
        val b = async { v.save() }
        a.await(); b.await()
        assertEquals(1, repo.observeLibrary().first().size)
        assertEquals(1, enqueued.size)
    }

    @Test fun `enqueue failure does not lose the saved song`() = runBlocking {
        enqueueFails = true
        val v = vm()
        v.fill()
        v.onAudioPicked(Uri.parse("content://media/audio/1"), "a.mp3").join()
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(1, repo.observeLibrary().first().size)
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.state.value.saveResult)
        v.onSaveResultHandled()
        assertNull(v.state.value.saveResult)
    }
}
