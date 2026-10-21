package com.lyricsmachine.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.app.store.SongSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class LibraryUiState(val songs: List<SongSummary> = emptyList(), val loaded: Boolean = false)

class LibraryViewModel(
    private val repository: SongRepository,
    private val cancelAnalysis: (Long) -> Unit,
    private val releasePersistablePermission: (Uri) -> Unit,
) : ViewModel() {
    val state: StateFlow<LibraryUiState> = repository.observeLibrary()
        .map { LibraryUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Stops any running analysis first so the worker cannot write rows for a deleted song. */
    fun delete(id: Long): Job = viewModelScope.launch {
        withContext(NonCancellable) {
            val audio = repository.get(id)?.audioUri
            try {
                cancelAnalysis(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort; deleting the song still proceeds.
            }
            repository.delete(id)
            if (audio != null && repository.countSongsWithAudio(audio) == 0) {
                try {
                    releasePersistablePermission(Uri.parse(audio))
                } catch (_: Exception) {
                }
            }
        }
    }
}
