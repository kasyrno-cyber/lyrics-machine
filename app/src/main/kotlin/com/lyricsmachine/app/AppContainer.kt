package com.lyricsmachine.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.lyricsmachine.app.analysis.chooseModel
import com.lyricsmachine.app.speech.ModelManager
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import com.lyricsmachine.app.speech.WhisperCppEngine
import com.lyricsmachine.app.store.AppDatabase
import com.lyricsmachine.app.store.SongMode
import com.lyricsmachine.app.store.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /** True when neither speech model is downloaded, so analysis and microphone singing must go through setup first. */
    suspend fun needsModelSetup(): Boolean = chooseModel(modelManager::awaitInitialState) == null

    /** True when singing [songId] uses the microphone (no analysed audio), which needs a speech model. */
    suspend fun singNeedsModel(songId: Long): Boolean {
        val mode = songRepository.observeLibrary().first().firstOrNull { it.id == songId }?.mode
        return mode == SongMode.LYRICS_ONLY || mode == SongMode.AUDIO_NOT_ANALYSED
    }

    /** Ready as soon as either model is Ready; otherwise reports BASE's state. */
    fun anyModelState(): Flow<ModelState> =
        combine(modelManager.state(ModelSpec.BASE), modelManager.state(ModelSpec.TINY)) { base, tiny ->
            if (base is ModelState.Ready) base else if (tiny is ModelState.Ready) tiny else base
        }

    fun releaseAudioPermission(uri: Uri) {
        try {
            applicationContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Grant was already gone.
        }
    }

    /** Whether [uri] can still be opened for reading. Blocking: call off Main. */
    fun isUriReadable(uri: String): Boolean = try {
        applicationContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
