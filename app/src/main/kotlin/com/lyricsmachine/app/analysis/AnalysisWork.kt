package com.lyricsmachine.app.analysis

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class AnalysisUiState(val progress: Float, val result: AnalysisResult?)

object AnalysisWork {
    fun uniqueName(songId: Long) = "analyse-$songId"

    fun enqueue(context: Context, songId: Long): UUID {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_SONG_ID to songId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(songId), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun cancel(context: Context, songId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(songId))
    }

    /** True while unique work for [songId] is ENQUEUED, RUNNING or BLOCKED. */
    suspend fun hasActiveWork(context: Context, songId: Long): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(songId)).first().any { isActive(it.state) }

    internal fun isActive(state: WorkInfo.State) =
        state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED

    fun observe(context: Context, songId: Long): Flow<AnalysisUiState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(songId)).map { infos ->
            val info = infos.lastOrNull()
            if (info == null) AnalysisUiState(0f, null) else toUiState(info.state, info.progress, info.outputData)
        }

    internal fun toUiState(state: WorkInfo.State, progress: Data, output: Data): AnalysisUiState = when (state) {
        WorkInfo.State.CANCELLED -> AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.CANCELLED))
        WorkInfo.State.FAILED -> AnalysisUiState(0f, AnalysisResult.Error(AnalysisError.ENGINE_FAILED))
        WorkInfo.State.SUCCEEDED -> AnalysisUiState(1f, resultOf(output))
        else -> AnalysisUiState(progress.getFloat(AnalysisWorker.KEY_PROGRESS, 0f), null)
    }

    private fun resultOf(output: Data): AnalysisResult {
        val error = output.getString(AnalysisWorker.KEY_ERROR)
        if (error != null) {
            val reason = AnalysisError.entries.firstOrNull { it.name == error } ?: AnalysisError.ENGINE_FAILED
            return AnalysisResult.Error(reason)
        }
        return AnalysisResult.Done(
            output.getDouble(AnalysisWorker.KEY_MATCH_RATIO, 0.0),
            output.getBoolean(AnalysisWorker.KEY_LOW_QUALITY, false),
        )
    }
}
