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

    private fun vm(songId: Long = -1L) = EditSongViewModel(
        songId = songId,
        repository = repo,
        readText = { importText },
        enqueueAnalysis = { enqueued += it },
        takePersistablePermission = { persisted += it },
    )

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
        v.onAudioPicked(Uri.parse("content://media/audio/1"), "take1.mp3")
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(1, enqueued.size)
        assertEquals(1, persisted.size)
        val song = repo.get(enqueued.single())
        assertEquals("content://media/audio/1", song?.audioUri)
    }

    @Test fun `imported lrc fills lyrics field`() = runBlocking {
        importText = "﻿[00:01.00]blue river walks\n[00:05.00]over quiet stones"
        val v = vm()
        v.importFile(Uri.parse("content://docs/1"), "demo.lrc")
        assertEquals("[00:01.00]blue river walks\n[00:05.00]over quiet stones", v.state.value.lyrics)
        assertNull(v.state.value.error)
    }

    @Test fun `oversize import shows error`() = runBlocking {
        importText = "la ".repeat(300_000)
        val v = vm()
        v.importFile(Uri.parse("content://docs/2"), "big.txt")
        assertEquals("This file is too large to be lyrics.", v.state.value.error)
        assertEquals("", v.state.value.lyrics)
    }

    @Test fun `rename only of analysed song does not re-analyse`() = runBlocking {
        val id = repo.upsert(
            SongEntity(title = "Old", artist = "A", language = Lang.EN, lyricsText = "blue river walks",
                audioUri = "content://media/audio/1", createdAt = 1L),
        )
        repo.saveAlignment(id, Alignment(listOf(LineTiming(0, 0, 1000)), emptyList(), 0.9), "tiny")
        val v = vm(id)
        v.awaitLoaded()
        v.onTitleChange("New name")
        assertEquals(SaveOutcome.SAVED_LYRICS_ONLY, v.save())
        assertTrue(enqueued.isEmpty())
        assertNotNull(repo.analysis(id))
        assertEquals("New name", repo.get(id)?.title)
    }

    @Test fun `editing lyrics of analysed song re-analyses`() = runBlocking {
        val id = repo.upsert(
            SongEntity(title = "Old", artist = "A", language = Lang.EN, lyricsText = "blue river walks",
                audioUri = "content://media/audio/1", createdAt = 1L),
        )
        repo.saveAlignment(id, Alignment(listOf(LineTiming(0, 0, 1000)), emptyList(), 0.9), "tiny")
        val v = vm(id)
        v.awaitLoaded()
        v.onLyricsChange("blue river runs")
        assertEquals(SaveOutcome.SAVED_NEEDS_ANALYSIS, v.save())
        assertEquals(listOf(id), enqueued)
    }
}
