package com.lyricsmachine.app.ui.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.lyrics.LyricsParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** [FAILED]: the song could not be stored; the error is shown and the screen stays open. */
enum class SaveOutcome { SAVED_LYRICS_ONLY, SAVED_NEEDS_ANALYSIS, INVALID_EMPTY_LYRICS, INVALID_EMPTY_TITLE, FAILED }

object EditCopy {
    const val SAVE_FAILED = "Couldn't save this song."
}

data class EditUiState(
    val title: String = "",
    val artist: String = "",
    val language: Lang = Lang.EN,
    val lyrics: String = "",
    val audioUri: String? = null,
    val audioName: String? = null,
    val error: String? = null,
    val dirty: Boolean = false,
    /** True while an existing song is being loaded; the form is disabled. */
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    /** One-shot result of the last save; the screen reacts, then calls [EditSongViewModel.onSaveResultHandled]. */
    val saveResult: SaveOutcome? = null,
    val savedId: Long = -1L,
)

class EditSongViewModel(
    private val songId: Long,
    private val repository: SongRepository,
    private val readText: suspend (Uri) -> String,
    private val enqueueAnalysis: (Long) -> Unit,
    private val takePersistablePermission: (Uri) -> Unit,
    private val releasePersistablePermission: (Uri) -> Unit,
    private val displayName: (String) -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(EditUiState(loading = songId >= 0))
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private var original = EditUiState()
    private var existing: SongEntity? = null

    /** Audio URIs granted during this edit session that are not (yet) stored on the song. */
    private val sessionGrants = mutableSetOf<String>()
    private var inFlightSave: Deferred<SaveOutcome>? = null

    private val loadJob: Job? = if (songId >= 0) viewModelScope.launch { load() } else null

    private suspend fun load() {
        val song = try {
            repository.get(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (song == null) {
            _state.update { it.copy(loading = false, loadFailed = true, error = "Couldn't open this song.") }
            return
        }
        existing = song
        val name = song.audioUri?.let { uri -> resolveName(uri) ?: uri }
        val loaded = EditUiState(
            title = song.title, artist = song.artist, language = song.language, lyrics = song.lyricsText,
            audioUri = song.audioUri, audioName = name,
        )
        original = loaded
        _state.value = loaded
    }

    private suspend fun resolveName(uri: String): String? = try {
        withContext(ioDispatcher) { displayName(uri) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
    fun onSaveResultHandled() = _state.update { it.copy(saveResult = null) }

    /** Releases [uri] unless some saved song still uses it. Never throws. */
    private suspend fun releaseIfUnused(uri: String) = withContext(NonCancellable) {
        try {
            if (repository.countSongsWithAudio(uri) == 0) releasePersistablePermission(Uri.parse(uri))
        } catch (_: Exception) {
        }
    }

    /** Keeps read access across restarts; never copies the file. Fails closed: no grant, no attachment. */
    fun onAudioPicked(uri: Uri, name: String? = null): Job = viewModelScope.launch {
        try {
            takePersistablePermission(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(error = "Couldn't keep access to this audio file.") }
            return@launch
        }
        val uriString = uri.toString()
        val label = name ?: resolveName(uriString) ?: uriString
        val previous = _state.value.audioUri
        if (uriString != existing?.audioUri) sessionGrants += uriString
        edit { it.copy(audioUri = uriString, audioName = label, error = null) }
        if (previous != null && previous != uriString && sessionGrants.remove(previous)) releaseIfUnused(previous)
    }

    fun removeAudio(): Job = viewModelScope.launch {
        val previous = _state.value.audioUri
        edit { it.copy(audioUri = null, audioName = null) }
        // A stored URI is released on save; an unsaved pick can go right away.
        if (previous != null && sessionGrants.remove(previous)) releaseIfUnused(previous)
    }

    /** Called when the user abandons the form: releases grants taken for audio that was never saved. */
    fun discardChanges(): Job = viewModelScope.launch {
        val abandoned = sessionGrants.toList()
        sessionGrants.clear()
        abandoned.forEach { releaseIfUnused(it) }
    }

    fun importFile(uri: Uri, fileName: String? = null): Job = viewModelScope.launch {
        val text = try {
            readText(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(error = "Couldn't read that file.") }
            return@launch
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            _state.update { it.copy(error = "This file is too large to be lyrics.") }
            return@launch
        }
        val name = fileName ?: resolveName(uri.toString())
        // LRC content is kept verbatim in the field; the parser handles timestamps at save and sing time.
        val clean = text.removePrefix("﻿")
        edit { s ->
            val guessed = name?.substringBeforeLast('.', "")?.trim().orEmpty()
            s.copy(lyrics = clean, error = null, title = if (s.title.isBlank() && guessed.isNotEmpty()) guessed else s.title)
        }
    }

    /**
     * Runs in [viewModelScope] so rotation cannot cancel it half way. A second call while one is running
     * waits for and returns the same outcome, so a double tap cannot insert twice.
     */
    suspend fun save(): SaveOutcome {
        inFlightSave?.let { if (it.isActive) return it.await() }
        val job = viewModelScope.async { doSave() }
        inFlightSave = job
        return job.await()
    }

    private suspend fun doSave(): SaveOutcome {
        loadJob?.join()
        val s = _state.value
        if (s.loadFailed) return SaveOutcome.INVALID_EMPTY_TITLE
        val invalid = when {
            s.title.isBlank() -> SaveOutcome.INVALID_EMPTY_TITLE
            s.lyrics.isBlank() || LyricsParser.parse(s.lyrics).tokens.isEmpty() -> SaveOutcome.INVALID_EMPTY_LYRICS
            else -> null
        }
        if (invalid != null) {
            _state.update { it.copy(saveResult = invalid) }
            return invalid
        }
        _state.update { it.copy(saving = true) }
        return try {
            store(s)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // e.g. a full disk: nothing was stored, so stay on the form and keep the session's grants.
            _state.update { it.copy(error = EditCopy.SAVE_FAILED, saveResult = SaveOutcome.FAILED) }
            SaveOutcome.FAILED
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    private suspend fun store(s: EditUiState): SaveOutcome {
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
        val (outcome, id) = withContext(NonCancellable) {
            val id = repository.upsert(entity)
            val changed = old == null || old.lyricsText != s.lyrics || old.audioUri != s.audioUri
            val needsAnalysis = s.audioUri != null && (changed || repository.loadTimings(id).first.isEmpty())
            // Grants for audio that ended up unused (replaced, removed, or picked then swapped) are released.
            val unused = buildSet {
                old?.audioUri?.let { add(it) }
                addAll(sessionGrants)
                s.audioUri?.let { remove(it) }
            }
            sessionGrants.clear()
            unused.forEach { releaseIfUnused(it) }
            if (needsAnalysis) {
                try {
                    enqueueAnalysis(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The song is saved; analysis can be retried from the analyse screen.
                }
            }
            (if (needsAnalysis) SaveOutcome.SAVED_NEEDS_ANALYSIS else SaveOutcome.SAVED_LYRICS_ONLY) to id
        }
        existing = entity.copy(id = id)
        original = s.copy(error = null, dirty = false)
        _state.update { it.copy(dirty = false, saveResult = outcome, savedId = id) }
        return outcome
    }

    companion object {
        const val MAX_IMPORT_BYTES = 512 * 1024
    }
}
