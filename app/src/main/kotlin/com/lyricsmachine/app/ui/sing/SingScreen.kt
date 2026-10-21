package com.lyricsmachine.app.ui.sing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyricsmachine.app.AppContainer
import com.lyricsmachine.app.live.LiveEvent
import com.lyricsmachine.app.live.LiveSession
import com.lyricsmachine.app.live.MicCapture
import com.lyricsmachine.app.playback.PlaybackSync
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import com.lyricsmachine.core.playback.PlaybackPosition
import com.lyricsmachine.core.tracker.TrackerSnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private class PlaybackSyncAdapter(private val sync: PlaybackSync) : PlaybackLike {
    override val position: StateFlow<PlaybackPosition> get() = sync.position
    override val isPlaying: StateFlow<Boolean> get() = sync.isPlaying
    override val positionMs: StateFlow<Long> get() = sync.positionMs
    override val durationMs: StateFlow<Long?> get() = sync.durationMs
    override val error: StateFlow<String?> get() = sync.error
    override fun play() = sync.play()
    override fun pause() = sync.pause()
    override fun seekToLine(lineIndex: Int) = sync.seekToLine(lineIndex)
    override fun close() = sync.close()
}

private class LiveSessionAdapter(private val session: LiveSession) : LiveLike {
    override val snapshot: StateFlow<TrackerSnapshot> get() = session.snapshot
    override val events: SharedFlow<LiveEvent> get() = session.events
    override fun start() = session.start()
    override fun stop() = session.stop()
    override fun jumpTo(lineIndex: Int) = session.jumpTo(lineIndex)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun modelFile(container: AppContainer, spec: ModelSpec) =
    (container.modelManager.state(spec).value as? ModelState.Ready)?.file
        ?: throw IllegalStateException("model $spec is not ready")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingScreen(container: AppContainer, songId: Long, manual: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val activity = remember(context) { context.findActivity() }
    val vm: SingViewModel = viewModel(
        key = "sing-$songId-$manual",
        factory = viewModelFactory {
            initializer {
                val repo = container.songRepository
                SingViewModel(
                    songId = songId,
                    manual = manual,
                    loadSong = { repo.get(it) },
                    songMode = { id -> repo.observeLibrary().first().firstOrNull { it.id == id }?.mode },
                    loadTimings = { repo.loadTimings(it) },
                    modelState = { container.modelManager.awaitInitialState(it) },
                    playbackFactory = { uri, lookup -> PlaybackSyncAdapter(PlaybackSync(app, Uri.parse(uri), lookup)) },
                    liveFactory = { lyrics, language, spec, scope ->
                        LiveSessionAdapter(
                            LiveSession(
                                lyrics = lyrics,
                                mic = MicCapture(),
                                engineFor = { s -> container.createSpeechEngine(modelFile(container, s)) },
                                language = language,
                                clock = SystemClock::elapsedRealtime,
                                scope = scope,
                                initialModel = spec,
                            ),
                        )
                    },
                )
            }
        },
    )
    val state by vm.state.collectAsState()

    // --- microphone permission ---
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var asking by rememberSaveable { mutableStateOf(false) }
    var deniedForGood by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking = false
        // After a denial, no rationale means the user chose "don't ask again" (or the system blocks the dialog).
        deniedForGood = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        vm.onMicPermissionResult(granted)
    }
    LaunchedEffect(state.requestMic) {
        if (!state.requestMic || asking) return@LaunchedEffect
        if (hasMic()) {
            vm.onMicPermissionResult(true)
        } else {
            asking = true
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun allowMic() {
        when {
            hasMic() -> vm.onMicPermissionResult(true)
            deniedForGood -> {
                vm.openedSettingsForMic()
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> vm.requestMicAgain()
        }
    }

    // --- lifecycle: stop on background (not on rotation), pick up a permission granted in settings ---
    val latest by rememberUpdatedState(state)
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) vm.onBackgrounded()
                Lifecycle.Event.ON_START -> if (latest.sentToSettings) vm.onReturnedFromSettings(hasMic())
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- keep the screen on while singing ---
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val snackbarHost = remember { SnackbarHostState() }
    val notice = state.snackbar
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        snackbarHost.showSnackbar(n.text)
        vm.snackbarShown(n.id)
    }

    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.mode == SingMode.PLAYBACK) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Use microphone instead") },
                                onClick = { menuOpen = false; vm.useMicrophone() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = { Controls(state, vm) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { msg ->
                MessageBanner(
                    text = msg,
                    action = state.messageAction,
                    onAllowMic = ::allowMic,
                    onUseMic = vm::useMicrophone,
                    onDismiss = vm::dismissMessage,
                )
            }
            val tracker = state.trackerState
            if (state.mode == SingMode.LIVE && state.isRunning && tracker != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        trackerLabel(tracker),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (state.loading) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator() }
            } else {
                LyricsView(
                    lines = state.lines,
                    currentLine = state.currentLine,
                    currentWord = state.currentWord,
                    wordProgress = state.wordProgress,
                    wordTimed = state.wordTimed,
                    onTapLine = vm::tapLine,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(
    text: String,
    action: MessageAction?,
    onAllowMic: () -> Unit,
    onUseMic: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tap = if (action == MessageAction.ALLOW_MIC) Modifier.clickable(onClick = onAllowMic) else Modifier
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().then(tap),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (action == MessageAction.USE_MIC) TextButton(onClick = onUseMic) { Text("Use microphone") }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
        }
    }
}

@Composable
private fun Controls(state: SingUiState, vm: SingViewModel) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (state.mode == SingMode.PLAYBACK) {
                val duration = state.durationMs
                val fraction = if (duration != null && duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.nudge(-1) }, modifier = Modifier.size(56.dp).semantics { contentDescription = "Previous line" }) {
                    Text("◀", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
                }
                if (state.mode != SingMode.MANUAL && state.canToggle) {
                    val label = when (state.mode) {
                        SingMode.PLAYBACK -> if (state.isRunning) "Pause" else "Play"
                        else -> if (state.isRunning) "Stop" else "Listen"
                    }
                    Button(onClick = vm::toggle, modifier = Modifier.widthIn(min = 120.dp)) { Text(label) }
                }
                IconButton(onClick = { vm.nudge(1) }, modifier = Modifier.size(56.dp).semantics { contentDescription = "Next line" }) {
                    Text("▶", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}
