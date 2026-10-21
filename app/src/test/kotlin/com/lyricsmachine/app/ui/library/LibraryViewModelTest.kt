package com.lyricsmachine.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
class LibraryViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SongRepository
    private val cancelled = mutableListOf<Long>()
    private val released = mutableListOf<Uri>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SongRepository(db, UnconfinedTestDispatcher()) { true }
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    private suspend fun add(audio: String?) = repo.upsert(
        SongEntity(title = "T", artist = "A", language = Lang.EN, lyricsText = "blue river", audioUri = audio, createdAt = 1L),
    )

    private fun vm() = LibraryViewModel(repo, { cancelled += it }, { released += it })

    @Test fun `delete cancels analysis, removes the song and releases its uri`() = runBlocking {
        val id = add("content://media/audio/1")
        vm().delete(id).join()
        assertEquals(listOf(id), cancelled)
        assertNull(repo.get(id))
        assertEquals(listOf("content://media/audio/1"), released.map { it.toString() })
    }

    @Test fun `delete does not release a uri shared by another song`() = runBlocking {
        val id = add("content://media/audio/1")
        add("content://media/audio/1")
        vm().delete(id).join()
        assertTrue(released.isEmpty())
        assertEquals(1, repo.observeLibrary().first().size)
    }
}
