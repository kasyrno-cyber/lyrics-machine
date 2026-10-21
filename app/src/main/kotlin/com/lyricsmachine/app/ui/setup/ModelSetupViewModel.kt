package com.lyricsmachine.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

object SetupCopy {
    const val DESCRIPTION =
        "Lyrics Machine needs a one-time download of its speech model (about 60 MB). After that, everything works offline."
    const val DAMAGED = "The download was damaged. Please try again."
    const val FAILED = "The download didn't finish. Check your connection and try again."
    const val SMALLER = "Use smaller model (faster, less accurate)"
}

data class SetupFailure(val headline: String, val detail: String?)

data class ModelSetupUiState(
    val spec: ModelSpec = ModelSpec.BASE,
    val model: ModelState = ModelState.Missing,
    val failure: SetupFailure? = null,
    /** One-shot: the chosen model is ready, so the screen continues to its destination. */
    val continueEvent: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModel(
    private val state: (ModelSpec) -> Flow<ModelState>,
    private val ensure: suspend (ModelSpec) -> Unit,
) : ViewModel() {
    private val spec = MutableStateFlow(ModelSpec.BASE)
    private val _state = MutableStateFlow(ModelSetupUiState())
    val state: StateFlow<ModelSetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            spec.flatMapLatest { s -> state(s).also { _state.update { u -> u.copy(spec = s) } } }
                .collect { m ->
                    _state.update { u ->
                        u.copy(
                            model = m,
                            failure = (m as? ModelState.Failed)?.let(::failureOf),
                            continueEvent = u.continueEvent || m is ModelState.Ready,
                        )
                    }
                }
        }
    }

    private fun failureOf(f: ModelState.Failed) =
        if (f.message == "checksum") SetupFailure(SetupCopy.DAMAGED, null)
        else SetupFailure(SetupCopy.FAILED, f.message.takeIf { it.isNotBlank() })

    /** Downloads the chosen model; also used by "Retry". Ignored while a download is running. */
    fun download() {
        if (_state.value.model is ModelState.Downloading) return
        val s = spec.value
        viewModelScope.launch {
            try {
                ensure(s)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ModelManager reports failures through its state flow.
            }
        }
    }

    fun useSmallerModel() {
        if (_state.value.model is ModelState.Downloading) return
        spec.value = ModelSpec.TINY
        download()
    }

    fun onContinueHandled() = _state.update { it.copy(continueEvent = false) }
}
