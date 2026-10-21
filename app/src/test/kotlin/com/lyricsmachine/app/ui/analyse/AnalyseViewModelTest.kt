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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyseViewModelTest {
    private val flows = mutableMapOf<UUID, MutableStateFlow<AnalysisUiState>>()
    private val oldRun = UUID.randomUUID()
    private val newRun = UUID.randomUUID()
    /** The run the view model follows by default; tests emit into it. */
    private val work = MutableStateFlow(AnalysisUiState(0f, null))
    private val model = MutableStateFlow<ModelState>(ModelState.Ready(File("m.bin")))
    private val enqueued = mutableListOf<Long>()
    private val enqueuedIfIdle = mutableListOf<Long>()
    private val cancelled = mutableListOf<Long>()
    private var retryRun = newRun
    private var activeId: UUID? = newRun
    private var hasResult = false

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        flows[newRun] = work
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(id: Long = 7L) = AnalyseViewModel(
        songId = id,
        observeById = { flows[it] ?: error("observed an unexpected run $it") },
        enqueue = { enqueued += it; retryRun },
        enqueueIfIdle = { enqueuedIfIdle += it; newRun },
        cancel = { cancelled += it },
        activeWorkId = { activeId },
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
        model.value = ModelState.Missing
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.MODEL_MISSING))
        assertEquals(AnalyseEvent.OpenSetup, vm.state.value.event)
        assertEquals(AnalysePhase.Error(AnalyseErrorKind.MODEL_MISSING), vm.state.value.phase)
        assertEquals("The speech model isn't downloaded yet.", AnalyseCopy.MODEL_MISSING)
    }

    @Test fun `model becoming ready after missing re-enqueues`() {
        model.value = ModelState.Missing
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.MODEL_MISSING))
        vm.onEventHandled()
        assertTrue(enqueued.isEmpty())
        val fresh = UUID.randomUUID()
        flows[fresh] = MutableStateFlow(AnalysisUiState(0f, null))
        retryRun = fresh
        model.value = ModelState.Ready(File("m.bin"))
        assertEquals(listOf(7L), enqueued)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `engine failure can be retried`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.ENGINE_FAILED))
        assertEquals(AnalysePhase.Error(AnalyseErrorKind.ENGINE_FAILED), vm.state.value.phase)
        assertTrue(vm.state.value.canContinue)
        val fresh = UUID.randomUUID()
        flows[fresh] = MutableStateFlow(AnalysisUiState(0f, null))
        retryRun = fresh
        vm.retry()
        assertEquals(listOf(7L), enqueued)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `cancelled work shows cancelled phase`() {
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.CANCELLED))
        assertEquals(AnalysePhase.Cancelled, vm.state.value.phase)
    }

    @Test fun `on entry with no work and no result enqueues once with the keep variant`() {
        activeId = null
        hasResult = false
        vm()
        assertEquals(listOf(7L), enqueuedIfIdle)
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `does not enqueue when work is active and follows that work`() {
        activeId = newRun
        val vm = vm()
        assertTrue(enqueued.isEmpty())
        assertTrue(enqueuedIfIdle.isEmpty())
        work.value = AnalysisUiState(0.3f, null)
        assertEquals(0.3f, vm.state.value.progress, 0.001f)
    }

    @Test fun `existing result with no work goes straight to sing without enqueue`() {
        activeId = null
        hasResult = true
        val vm = vm()
        assertTrue(enqueued.isEmpty())
        assertTrue(enqueuedIfIdle.isEmpty())
        assertEquals(AnalysePhase.Done, vm.state.value.phase)
        assertEquals(AnalyseEvent.OpenSing, vm.state.value.event)
    }

    @Test fun `stale terminal results of an old run are never observed`() {
        activeId = null
        flows[oldRun] = MutableStateFlow(AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.MODEL_MISSING)))
        val vm = vm()
        assertEquals(listOf(7L), enqueuedIfIdle)
        assertNull(vm.state.value.event)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `a stale done is not followed`() {
        activeId = null
        flows[oldRun] = MutableStateFlow(AnalysisUiState(1f, AnalysisResult.Done(0.9, false)))
        val vm = vm()
        assertNull(vm.state.value.event)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }

    @Test fun `retry follows the newly enqueued run`() {
        val second = UUID.randomUUID()
        flows[second] = MutableStateFlow(AnalysisUiState(0.6f, null))
        retryRun = second
        val vm = vm()
        work.value = AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.ENGINE_FAILED))
        vm.retry()
        assertEquals(listOf(7L), enqueued)
        assertEquals(0.6f, vm.state.value.progress, 0.001f)
        assertEquals(AnalysePhase.Running, vm.state.value.phase)
    }
}
