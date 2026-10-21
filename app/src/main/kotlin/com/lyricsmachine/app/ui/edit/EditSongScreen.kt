package com.lyricsmachine.app.ui.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyricsmachine.app.AppContainer
import com.lyricsmachine.app.analysis.AnalysisWork
import com.lyricsmachine.core.Lang
import kotlinx.coroutines.launch

private fun languageLabel(lang: Lang) = when (lang) {
    Lang.EN -> "English"
    Lang.MS -> "Bahasa Malaysia"
    Lang.ID -> "Bahasa Indonesia"
    Lang.MIXED -> "Mixed"
}

private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

private fun readCapped(context: Context, uri: Uri): String {
    val limit = EditSongViewModel.MAX_IMPORT_BYTES + 1
    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open")
    input.use {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < limit) {
            val n = it.read(chunk, 0, minOf(chunk.size, limit - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return String(buf.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongScreen(
    container: AppContainer,
    songId: Long,
    onBack: () -> Unit,
    onSavedLyricsOnly: () -> Unit,
    onNeedsAnalysis: (Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: EditSongViewModel = viewModel(
        key = "edit-$songId",
        factory = viewModelFactory {
            initializer {
                val app = container.applicationContext
                EditSongViewModel(
                    songId = songId,
                    repository = container.songRepository,
                    readText = { uri -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readCapped(app, uri) } },
                    enqueueAnalysis = { AnalysisWork.enqueue(app, it) },
                    takePersistablePermission = { uri ->
                        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    releasePersistablePermission = container::releaseAudioPermission,
                    displayName = { queryDisplayName(app, Uri.parse(it)) },
                    uriReadable = container::isUriReadable,
                )
            }
        },
    )
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDiscard by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onAudioPicked(uri)
    }

    LaunchedEffect(state.saveResult) {
        val result = state.saveResult
        when (result) {
            null -> Unit
            SaveOutcome.INVALID_EMPTY_TITLE -> titleError = true
            SaveOutcome.INVALID_EMPTY_LYRICS -> lyricsError = true
            SaveOutcome.SAVED_LYRICS_ONLY -> onSavedLyricsOnly()
            SaveOutcome.SAVED_NEEDS_ANALYSIS -> onNeedsAnalysis(state.savedId)
            SaveOutcome.FAILED -> Unit // the error snackbar explains; stay on the form
        }
        if (result != null) vm.onSaveResultHandled()
    }

    LaunchedEffect(state.error) {
        if (!state.loadFailed) state.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    fun requestBack() { if (state.dirty) showDiscard = true else onBack() }
    BackHandler(onBack = ::requestBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (songId >= 0) "Edit song" else "Add song") },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (state.loadFailed) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Couldn't open this song.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                }
            } else if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { vm.onTitleChange(it); titleError = false },
                    label = { Text("Title") },
                    isError = titleError,
                    supportingText = if (titleError) ({ Text("Enter a title.") }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.artist,
                    onValueChange = vm::onArtistChange,
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LanguageDropdown(state.language, vm::onLanguageChange)
                OutlinedTextField(
                    value = state.lyrics,
                    onValueChange = { vm.onLyricsChange(it); lyricsError = false },
                    label = { Text("Lyrics") },
                    isError = lyricsError,
                    supportingText = if (lyricsError) ({ Text("Enter some lyrics.") }) else null,
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Import .txt/.lrc") }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { audioLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Attach audio") }
                    if (state.audioUri != null) {
                        TextButton(onClick = vm::removeAudio, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove audio") }
                    }
                }
                state.audioUri?.let {
                    Text(state.audioName ?: it, style = MaterialTheme.typography.bodyMedium)
                }
                state.audioWarning?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        scope.launch { vm.save() }
                    },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Save") }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { showDiscard = false; vm.discardChanges(); onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(selected: Lang, onSelect: (Lang) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = languageLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Lang.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(languageLabel(lang)) },
                    onClick = { onSelect(lang); expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}
