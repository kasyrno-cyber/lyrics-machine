package com.lyricsmachine.app.ui.analyse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.analysis.AnalysisError
import com.lyricsmachine.app.analysis.AnalysisResult
import com.lyricsmachine.app.analysis.AnalysisUiState
import com.lyricsmachine.app.speech.ModelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

object AnalyseCopy {
    const val LOW_QUALITY = "Timing may be rough — check the lyrics match this recording."
    const val AUDIO_UNREADABLE =
        "This audio file can't be opened. The lyrics are saved — you can still sing with the microphone."
    const val MODEL_MISSING = "The speech model isn't downloaded yet."
    const val ENGINE_FAILED = "Analysis failed. You can try again or sing with the microphone."
    const val CANCELLED = "Analysis was cancelled."
}

enum class AnalyseErrorKind { AUDIO_UNREADABLE, MODEL_MISSING, ENGINE_FAILED }

sealed interface AnalysePhase {
    data object Running : AnalysePhase
    data object Done : AnalysePhase
    data object LowQuality : AnalysePhase
    data class Error(val kind: AnalyseErrorKind) : AnalysePhase
    data object Cancelled : AnalysePhase
}

/** One-shot navigation requests the screen performs, then acknowledges with [AnalyseViewModel.onEventHandled]. */
enum class AnalyseEvent { OpenSing, OpenSetup }

data class AnalyseScreenState(
    val progress: Float = 0f,
    val phase: AnalysePhase = AnalysePhase.Running,
    /** True when the user can move on to singing (accurately or with the microphone). */
    val canContinue: Boolean = false,
    val event: AnalyseEvent? = null,
)

class AnalyseViewModel(
    private val songId: Long,
    /** Observes exactly one run, so results of earlier runs for the same song are never seen. */
    private val observeById: (UUID) -> Flow<AnalysisUiState>,
    /** Starts a fresh run, replacing any earlier one. Used by [retry]. */
    private val enqueue: (Long) -> UUID,
    /** Starts a run only if none is active (KEEP); returns the id of the run that is active afterwards. */
    private val enqueueIfIdle: suspend (Long) -> UUID,
    private val cancel: (Long) -> Unit,
    /** Id of the ENQUEUED/RUNNING/BLOCKED run for this song, if any. */
    private val activeWorkId: suspend (Long) -> UUID?,
    private val hasAnalysis: suspend (Long) -> Boolean,
    /** Reports whether any speech model is available; used to resume after the setup screen. */
    private val modelState: Flow<ModelState>,
) : ViewModel() {
    private val _state = MutableStateFlow(AnalyseScreenState())
    val state: StateFlow<AnalyseScreenState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            val active = safely { activeWorkId(songId) }
            when {
                active != null -> follow(active)
                // A finished analysis exists and nothing is running: nothing to wait for.
                safely { hasAnalysis(songId) } == true -> _state.update {
                    it.copy(progress = 1f, phase = AnalysePhase.Done, canContinue = true, event = it.event ?: AnalyseEvent.OpenSing)
                }
                // An enqueue that failed during save, or was lost with the process, is redone here.
                else -> startWork { enqueueIfIdle(songId) }
            }
        }
        viewModelScope.launch {
            modelState.collect { m ->
                if (m is ModelState.Ready && _state.value.phase == AnalysePhase.Error(AnalyseErrorKind.MODEL_MISSING)) retry()
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun follow(id: UUID) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch { observeById(id).collect { apply(it) } }
    }

    private suspend fun startWork(start: suspend () -> UUID) {
        val id = safely(start)
        if (id == null) {
            _state.update { it.copy(phase = AnalysePhase.Error(AnalyseErrorKind.ENGINE_FAILED), canContinue = true) }
        } else {
            follow(id)
        }
    }

    private fun apply(ui: AnalysisUiState) {
        val progress = ui.progress.coerceIn(0f, 1f)
        val result = ui.result
        _state.update { s ->
            when (result) {
                null -> s.copy(progress = progress, phase = AnalysePhase.Running, canContinue = false)
                is AnalysisResult.Done ->
                    if (result.lowQuality) {
                        s.copy(progress = 1f, phase = AnalysePhase.LowQuality, canContinue = true)
                    } else {
                        s.copy(
                            progress = 1f, phase = AnalysePhase.Done, canContinue = true,
                            event = s.event ?: AnalyseEvent.OpenSing,
                        )
                    }
                is AnalysisResult.Error -> when (result.reason) {
                    AnalysisError.AUDIO_UNREADABLE ->
                        s.copy(progress = progress, phase = AnalysePhase.Error(AnalyseErrorKind.AUDIO_UNREADABLE), canContinue = true)
                    AnalysisError.MODEL_MISSING -> s.copy(
                        progress = progress, phase = AnalysePhase.Error(AnalyseErrorKind.MODEL_MISSING), canContinue = false,
                        event = if (s.phase == AnalysePhase.Error(AnalyseErrorKind.MODEL_MISSING)) s.event else AnalyseEvent.OpenSetup,
                    )
                    AnalysisError.ENGINE_FAILED ->
                        s.copy(progress = progress, phase = AnalysePhase.Error(AnalyseErrorKind.ENGINE_FAILED), canContinue = true)
                    AnalysisError.CANCELLED -> s.copy(progress = 0f, phase = AnalysePhase.Cancelled, canContinue = false)
                }
            }
        }
    }

    fun cancel() = cancel(songId)

    /** Starts the analysis again (after a failure, a cancel, or once the model is downloaded). */
    fun retry() {
        _state.update { it.copy(progress = 0f, phase = AnalysePhase.Running, canContinue = false) }
        viewModelScope.launch { startWork { enqueue(songId) } }
    }

    fun onEventHandled() = _state.update { it.copy(event = null) }
}
