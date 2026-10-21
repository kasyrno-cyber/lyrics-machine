package com.lyricsmachine.app.ui.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.lyrics.LyricsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

enum class SaveOutcome { SAVED_LYRICS_ONLY, SAVED_NEEDS_ANALYSIS, INVALID_EMPTY_LYRICS, INVALID_EMPTY_TITLE }

data class EditUiState(
    val title: String = "",
    val artist: String = "",
    val language: Lang = Lang.EN,
    val lyrics: String = "",
    val audioUri: String? = null,
    val audioName: String? = null,
    val error: String? = null,
    val dirty: Boolean = false,
    val loading: Boolean = false,
)

class EditSongViewModel(
    private val songId: Long,
    private val repository: SongRepository,
    private val readText: suspend (Uri) -> String,
    private val enqueueAnalysis: (Long) -> Unit,
    private val takePersistablePermission: (Uri) -> Unit,
    private val displayName: (String) -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(EditUiState(loading = songId >= 0))
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private var original = EditUiState()
    private var existing: SongEntity? = null

    init {
        if (songId >= 0) viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val song = repository.get(songId)
        existing = song
        if (song == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val loaded = EditUiState(
            title = song.title, artist = song.artist, language = song.language, lyrics = song.lyricsText,
            audioUri = song.audioUri, audioName = song.audioUri?.let { displayName(it) ?: it },
        )
        original = loaded
        _state.value = loaded
    }

    private fun edit(block: (EditUiState) -> EditUiState) = _state.update { s ->
        val n = block(s)
        n.copy(dirty = isDirty(n))
    }

    private fun isDirty(s: EditUiState) = s.title != original.title || s.artist != original.artist ||
        s.language != original.language || s.lyrics != original.lyrics || s.audioUri != original.audioUri

    fun onTitleChange(v: String) = edit { it.copy(title = v, error = null) }
    fun onArtistChange(v: String) = edit { it.copy(artist = v) }
    fun onLanguageChange(v: Lang) = edit { it.copy(language = v) }
    fun onLyricsChange(v: String) = edit { it.copy(lyrics = v, error = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Called after the user picked an audio document. Keeps read access across restarts; never copies the file. */
    fun onAudioPicked(uri: Uri, name: String?) {
        try {
            takePersistablePermission(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Some providers don't offer persistable grants; the grant may still last for this session.
        }
        edit { it.copy(audioUri = uri.toString(), audioName = name ?: uri.toString()) }
    }

    fun removeAudio() = edit { it.copy(audioUri = null, audioName = null) }

    suspend fun importFile(uri: Uri, fileName: String?) {
        val text = try {
            readText(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(error = "Couldn't read that file.") }
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            _state.update { it.copy(error = "This file is too large to be lyrics.") }
            return
        }
        // LRC content is kept verbatim in the field; the parser handles timestamps at save and sing time.
        val clean = text.removePrefix("﻿")
        edit { s ->
            val guessed = fileName?.substringBeforeLast('.', "")?.trim().orEmpty()
            s.copy(lyrics = clean, error = null, title = if (s.title.isBlank() && guessed.isNotEmpty()) guessed else s.title)
        }
    }

    suspend fun save(): SaveOutcome {
        val s = _state.value
        if (s.title.isBlank()) return SaveOutcome.INVALID_EMPTY_TITLE
        if (s.lyrics.isBlank() || LyricsParser.parse(s.lyrics).tokens.isEmpty()) return SaveOutcome.INVALID_EMPTY_LYRICS
        val old = existing
        val entity = SongEntity(
            id = old?.id ?: 0L,
            title = s.title.trim(),
            artist = s.artist.trim(),
            language = s.language,
            lyricsText = s.lyrics,
            audioUri = s.audioUri,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
        )
        val id = repository.upsert(entity)
        val needsAnalysis = s.audioUri != null && (
            old == null || old.lyricsText != s.lyrics || old.audioUri != s.audioUri || repository.analysis(id) == null
            )
        // A second save from the same screen is an edit of the row we just wrote.
        existing = entity.copy(id = id)
        original = s.copy(error = null, dirty = false)
        _state.update { it.copy(dirty = false) }
        if (!needsAnalysis) return SaveOutcome.SAVED_LYRICS_ONLY
        enqueueAnalysis(id)
        lastSavedId = id
        return SaveOutcome.SAVED_NEEDS_ANALYSIS
    }

    /** Id of the most recent song saved with [SaveOutcome.SAVED_NEEDS_ANALYSIS], for navigation. */
    var lastSavedId: Long = -1L
        private set

    companion object {
        const val MAX_IMPORT_BYTES = 512 * 1024
    }
}
