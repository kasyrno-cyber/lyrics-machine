package com.lyricsmachine.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongRepository
import kotlin.coroutines.cancellation.CancellationException

/** Manual DI container; services are added by later tasks. */
class AppContainer(val applicationContext: Context) {
    private val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "lyrics-machine.db").build()
    }

    val songRepository: SongRepository by lazy {
        SongRepository(database, uriReadable = ::isUriReadable)
    }

    private fun isUriReadable(uri: String): Boolean = try {
        applicationContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
