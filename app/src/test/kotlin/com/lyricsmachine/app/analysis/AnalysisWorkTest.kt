package com.lyricsmachine.app.analysis

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisWorkTest {
    @Test fun `running state reports progress`() {
        val s = AnalysisWork.toUiState(WorkInfo.State.RUNNING, workDataOf("progress" to 0.4f), Data.EMPTY)
        assertEquals(AnalysisUiState(0.4f, null), s)
    }

    @Test fun `succeeded done output`() {
        val s = AnalysisWork.toUiState(WorkInfo.State.SUCCEEDED, Data.EMPTY, AnalysisWorker.outputOf(AnalysisResult.Done(0.3, true)))
        assertEquals(AnalysisUiState(1f, AnalysisResult.Done(0.3, true)), s)
    }

    @Test fun `succeeded error output`() {
        val out = AnalysisWorker.outputOf(AnalysisResult.Error(AnalysisError.MODEL_MISSING))
        assertEquals(AnalysisResult.Error(AnalysisError.MODEL_MISSING), AnalysisWork.toUiState(WorkInfo.State.SUCCEEDED, Data.EMPTY, out).result)
    }

    @Test fun `cancelled state maps to CANCELLED`() {
        assertEquals(AnalysisResult.Error(AnalysisError.CANCELLED), AnalysisWork.toUiState(WorkInfo.State.CANCELLED, Data.EMPTY, Data.EMPTY).result)
    }
}
