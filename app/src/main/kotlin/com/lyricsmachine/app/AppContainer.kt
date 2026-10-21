package com.lyricsmachine.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongRepository
import java.io.FileNotFoundException

/** Manual DI container; services are added by later tasks. */
class AppContainer(val applicationContext: Context) {
    private val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "lyrics-machine.db").build()
    }

    val songRepository: SongRepository by lazy {
        SongRepository(database, ::isUriReadable)
    }

    private fun isUriReadable(uri: String): Boolean = try {
        applicationContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: FileNotFoundException) {
        false
    }
}
