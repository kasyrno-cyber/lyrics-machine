package com.lyricsmachine.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.app.store.SongSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(val songs: List<SongSummary> = emptyList(), val loaded: Boolean = false)

class LibraryViewModel(private val repository: SongRepository) : ViewModel() {
    val state: StateFlow<LibraryUiState> = repository.observeLibrary()
        .map { LibraryUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
