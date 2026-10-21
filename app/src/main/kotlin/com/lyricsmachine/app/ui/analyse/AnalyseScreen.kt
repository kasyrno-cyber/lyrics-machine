package com.lyricsmachine.app.ui.analyse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyricsmachine.app.AppContainer
import com.lyricsmachine.app.analysis.AnalysisWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyseScreen(
    container: AppContainer,
    songId: Long,
    onSing: () -> Unit,
    onEditLyrics: () -> Unit,
    onNeedsModel: () -> Unit,
    onLeave: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val vm: AnalyseViewModel = viewModel(
        key = "analyse-$songId",
        factory = viewModelFactory {
            initializer {
                AnalyseViewModel(
                    songId = songId,
                    observeById = { AnalysisWork.observeById(app, it) },
                    enqueue = { AnalysisWork.enqueue(app, it) },
                    enqueueIfIdle = { AnalysisWork.enqueueIfIdle(app, it) },
                    cancel = { AnalysisWork.cancel(app, it) },
                    activeWorkId = { AnalysisWork.activeWorkId(app, it) },
                    hasAnalysis = { container.songRepository.analysis(it) != null },
                    modelState = container.anyModelState(),
                )
            }
        },
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(state.event) {
        when (state.event) {
            AnalyseEvent.OpenSing -> onSing()
            AnalyseEvent.OpenSetup -> onNeedsModel()
            null -> Unit
        }
        if (state.event != null) vm.onEventHandled()
    }

    fun cancelAndLeave() {
        vm.cancel()
        onLeave()
    }
    BackHandler(enabled = state.phase == AnalysePhase.Running) { cancelAndLeave() }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Analysing") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val phase = state.phase) {
                    AnalysePhase.Running -> {
                        Text("Listening to the song to time the lyrics…", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        ActionButton("Cancel", primary = false, onClick = ::cancelAndLeave)
                    }
                    AnalysePhase.Done -> Text("Analysis complete.", style = MaterialTheme.typography.bodyLarge)
                    AnalysePhase.LowQuality -> {
                        Text(AnalyseCopy.LOW_QUALITY, style = MaterialTheme.typography.bodyLarge)
                        ActionButton("Sing anyway", primary = true, onClick = onSing)
                        ActionButton("Edit lyrics", primary = false, onClick = onEditLyrics)
                    }
                    is AnalysePhase.Error -> when (phase.kind) {
                        AnalyseErrorKind.AUDIO_UNREADABLE -> {
                            Text(AnalyseCopy.AUDIO_UNREADABLE, style = MaterialTheme.typography.bodyLarge)
                            ActionButton("Sing with microphone", primary = true, onClick = onSing)
                            ActionButton("Back", primary = false, onClick = onLeave)
                        }
                        AnalyseErrorKind.MODEL_MISSING -> {
                            Text(AnalyseCopy.MODEL_MISSING, style = MaterialTheme.typography.bodyLarge)
                            ActionButton("Download", primary = true, onClick = onNeedsModel)
                            ActionButton("Back", primary = false, onClick = onLeave)
                        }
                        AnalyseErrorKind.ENGINE_FAILED -> {
                            Text(AnalyseCopy.ENGINE_FAILED, style = MaterialTheme.typography.bodyLarge)
                            ActionButton("Try again", primary = true, onClick = vm::retry)
                            ActionButton("Sing with microphone", primary = false, onClick = onSing)
                        }
                    }
                    AnalysePhase.Cancelled -> {
                        Text(AnalyseCopy.CANCELLED, style = MaterialTheme.typography.bodyLarge)
                        ActionButton("Try again", primary = true, onClick = vm::retry)
                        ActionButton("Back", primary = false, onClick = onLeave)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}
