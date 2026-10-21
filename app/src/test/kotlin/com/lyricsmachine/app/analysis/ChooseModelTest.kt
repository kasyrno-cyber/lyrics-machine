package com.lyricsmachine.app.analysis

import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Plain JVM: model precedence for analysis. */
class ChooseModelTest {
    private val baseFile = File("base.bin")
    private val tinyFile = File("tiny.bin")

    private fun states(base: ModelState, tiny: ModelState): suspend (ModelSpec) -> ModelState =
        { if (it == ModelSpec.BASE) base else tiny }

    @Test fun `base ready wins`() = runBlocking {
        assertEquals(ModelSpec.BASE to baseFile, chooseModel(states(ModelState.Ready(baseFile), ModelState.Ready(tinyFile))))
    }

    @Test fun `only tiny ready gives tiny`() = runBlocking {
        assertEquals(ModelSpec.TINY to tinyFile, chooseModel(states(ModelState.Downloading(0.5f), ModelState.Ready(tinyFile))))
    }

    @Test fun `none ready gives null`() = runBlocking {
        assertNull(chooseModel(states(ModelState.Missing, ModelState.Failed("x"))))
    }
}
