package com.lyricsmachine.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.lyricsmachine.app.speech.ModelManager
import com.lyricsmachine.app.speech.WhisperCppEngine
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongRepository
import okhttp3.OkHttpClient
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Manual DI container; services are added by later tasks. */
class AppContainer(val applicationContext: Context) {
    private val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "lyrics-machine.db").build()
    }

    val songRepository: SongRepository by lazy {
        SongRepository(database, uriReadable = ::isUriReadable)
    }

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val modelManager: ModelManager by lazy {
        ModelManager(File(applicationContext.filesDir, "models"), httpClient)
    }

    /** whisper.cpp threads: leave one core for the UI/audio, cap at 4 (more gives little on phones). */
    val whisperThreads: Int = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 1))

    fun createSpeechEngine(modelFile: File): WhisperCppEngine = WhisperCppEngine(modelFile, whisperThreads)

    private fun isUriReadable(uri: String): Boolean = try {
        applicationContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
