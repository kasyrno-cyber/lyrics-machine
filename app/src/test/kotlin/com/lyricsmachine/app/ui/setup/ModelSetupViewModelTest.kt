package com.lyricsmachine.app.ui.setup

import com.lyricsmachine.app.speech.ModelSpec
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
class ModelSetupViewModelTest {
    private val states = ModelSpec.entries.associateWith { MutableStateFlow<ModelState>(ModelState.Missing) }
    private val ensured = mutableListOf<ModelSpec>()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = ModelSetupViewModel(state = { states.getValue(it) }, ensure = { ensured += it })

    @Test fun `download starts the base model`() {
        val vm = vm()
        vm.download()
        assertEquals(listOf(ModelSpec.BASE), ensured)
    }

    @Test fun `progress is exposed`() {
        val vm = vm()
        vm.download()
        states.getValue(ModelSpec.BASE).value = ModelState.Downloading(0.5f)
        assertEquals(ModelState.Downloading(0.5f), vm.state.value.model)
    }

    @Test fun `failure then retry downloads again`() {
        val vm = vm()
        vm.download()
        states.getValue(ModelSpec.BASE).value = ModelState.Failed("timeout")
        val failure = vm.state.value.failure
        assertEquals("timeout", failure?.detail)
        vm.download()
        assertEquals(listOf(ModelSpec.BASE, ModelSpec.BASE), ensured)
    }

    @Test fun `download is not restarted while downloading`() {
        val vm = vm()
        states.getValue(ModelSpec.BASE).value = ModelState.Downloading(0.1f)
        vm.download()
        assertTrue(ensured.isEmpty())
    }

    @Test fun `ready emits a one shot continue event`() {
        val vm = vm()
        assertFalse(vm.state.value.continueEvent)
        states.getValue(ModelSpec.BASE).value = ModelState.Ready(File("m.bin"))
        assertTrue(vm.state.value.continueEvent)
        vm.onContinueHandled()
        assertFalse(vm.state.value.continueEvent)
    }

    @Test fun `checksum failure shows damaged download copy`() {
        val vm = vm()
        states.getValue(ModelSpec.BASE).value = ModelState.Failed("checksum")
        val failure = vm.state.value.failure
        assertEquals("The download was damaged. Please try again.", failure?.headline)
        assertNull(failure?.detail)
    }

    @Test fun `smaller model downloads tiny and continues when tiny is ready`() {
        val vm = vm()
        vm.useSmallerModel()
        assertEquals(listOf(ModelSpec.TINY), ensured)
        assertEquals(ModelSpec.TINY, vm.state.value.spec)
        states.getValue(ModelSpec.TINY).value = ModelState.Ready(File("t.bin"))
        assertTrue(vm.state.value.continueEvent)
    }

    @Test fun `copy matches the brief`() {
        assertEquals(
            "Lyrics Machine needs a one-time download of its speech model (about 60 MB). After that, everything works offline.",
            SetupCopy.DESCRIPTION,
        )
    }
}
