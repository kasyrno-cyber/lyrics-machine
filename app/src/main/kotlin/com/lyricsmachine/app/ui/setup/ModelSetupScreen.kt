package com.lyricsmachine.app.ui.setup

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyricsmachine.app.AppContainer
import com.lyricsmachine.app.speech.ModelState

private fun megabytes(bytes: Long) = "%.0f MB".format(bytes / 1_000_000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetupScreen(
    container: AppContainer,
    showLyricsOnly: Boolean,
    onLyricsOnly: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: ModelSetupViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ModelSetupViewModel(
                    state = { container.modelManager.state(it) },
                    ensure = { container.modelManager.ensure(it) },
                )
            }
        },
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(state.continueEvent) {
        if (state.continueEvent) {
            vm.onContinueHandled()
            onContinue()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Speech model") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(SetupCopy.DESCRIPTION, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Download size: ${megabytes(state.spec.sizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val m = state.model) {
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(m.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    is ModelState.Ready -> Text("Ready.", style = MaterialTheme.typography.bodyMedium)
                    is ModelState.Failed -> {
                        state.failure?.let { f ->
                            Text(f.headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                            f.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Button(onClick = vm::download, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                    }
                    ModelState.Missing ->
                        Button(onClick = vm::download, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download") }
                }
                if (showLyricsOnly) {
                    TextButton(onClick = onLyricsOnly, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Just show the lyrics")
                    }
                }
                if (state.model !is ModelState.Downloading && state.model !is ModelState.Ready) {
                    TextButton(onClick = vm::useSmallerModel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(SetupCopy.SMALLER)
                    }
                }
            }
        }
    }
}
