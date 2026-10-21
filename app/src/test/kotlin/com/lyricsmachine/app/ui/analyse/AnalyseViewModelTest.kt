package com.lyricsmachine.app.ui.analyse

import com.lyricsmachine.app.analysis.AnalysisError
import com.lyricsmachine.app.analysis.AnalysisResult
import com.lyricsmachine.app.analysis.AnalysisUiState
import com.lyricsmachine.app.speech.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyseViewModelTest {
    private val work = MutableStateFlow(AnalysisUiState(0f, null))
    private val model = MutableStateFlow<ModelState>(ModelState.Ready(File("m.bin")))
    private val enqueued = mutableListOf<Long>()
    private val cancelled = mutableListOf<Long>()
    private var active = true
    private var hasResult = false

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(id: Long = 7L) = AnalyseViewModel(
        songId = id,
        observe = { work },
        enqueue = { enqueued += it },
        cancel = { cancelled += it },
        hasActiveWork = { active },
        hasAnalysis = { hasResult },
        modelState = model,
    )

    @Test fun `progress mirrors work state`() {
        val vm = vm()
        work.value = AnalysisUiState(0.4f, null)
        assertEquals(0.4f, vm.state.value.progress, 0.001f)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
        assertFalse(vm.state.value.canContinue)
    }

    @Test fun `done navigates to sing`() {
        val vm = vm()
        work.value = AnalysisUiState(1f, AnalysisResult.Done(0.9, lowQuality = false))
        assertEquals(AnalysePhase.Done, vm.state.value.phase)
        assertEquals(AnalyseEvent.OpenSing, vm.state.value.event)
        vm.onEventHandled()
        assertNull(vm.state.value.event)
    }

    @Test fun `low quality shows warning but allows continue`() {
        val vm = vm()
        work.value = AnalysisUiState(1f, AnalysisResult.Done(0.3, lowQuality = true))
        assertEquals(AnalysePhase.LowQuality, vm.state.value.phase)
        assertTrue(vm.state.value.canContinue)
        assertNull(vm.state.value.event)
        assertEquals("Timing may be rough — check the lyrics match this recording.", AnalyseCopy.LOW_QUALITY)
    }

    @Test fun `audio unreadable shows message`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE))
        assertEquals(AnalysePhase.Error(AnalyseErrorKind.AUDIO_UNREADABLE), vm.state.value.phase)
        assertTrue(vm.state.value.canContinue)
        assertEquals(
            "This audio file can't be opened. The lyrics are saved — you can still sing with the microphone.",
            AnalyseCopy.AUDIO_UNREADABLE,
        )
    }

    @Test fun `cancel stops work`() {
        val vm = vm()
        vm.cancel()
        assertEquals(listOf(7L), cancelled)
    }

    @Test fun `model missing routes to setup`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.MODEL_MISSING))
        assertEquals(AnalyseEvent.OpenSetup, vm.state.value.event)
        assertEquals(AnalysePhase.Error(AnalyseErrorKind.MODEL_MISSING), vm.state.value.phase)
        assertEquals("The speech model isn't downloaded yet.", AnalyseCopy.MODEL_MISSING)
    }

    @Test fun `model becoming ready after missing re-enqueues`() {
        val vm = vm()
        model.value = ModelState.Missing
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.MODEL_MISSING))
        vm.onEventHandled()
        assertTrue(enqueued.isEmpty())
        model.value = ModelState.Ready(File("m.bin"))
        assertEquals(listOf(7L), enqueued)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `engine failure can be retried`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.ENGINE_FAILED))
        assertEquals(AnalysePhase.Error(AnalyseErrorKind.ENGINE_FAILED), vm.state.value.phase)
        assertTrue(vm.state.value.canContinue)
        vm.retry()
        assertEquals(listOf(7L), enqueued)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `cancelled work shows cancelled phase`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.CANCELLED))
        assertEquals(AnalysePhase.Cancelled, vm.state.value.phase)
    }

    @Test fun `enqueues itself when no work is active and no result exists`() {
        active = false
        hasResult = false
        vm()
        assertEquals(listOf(7L), enqueued)
    }

    @Test fun `does not enqueue twice when work is active`() {
        active = true
        hasResult = false
        vm()
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `does not enqueue when a result already exists`() {
        active = false
        hasResult = true
        vm()
        assertTrue(enqueued.isEmpty())
    }
}
