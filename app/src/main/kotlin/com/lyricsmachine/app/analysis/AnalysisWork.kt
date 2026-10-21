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

    private fun request(songId: Long) = OneTimeWorkRequestBuilder<AnalysisWorker>()
        .setInputData(workDataOf(AnalysisWorker.KEY_SONG_ID to songId))
        .build()

    /** Starts a fresh run, replacing any earlier one. Returns the id of the new run. */
    fun enqueue(context: Context, songId: Long): UUID {
        val request = request(songId)
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(songId), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /**
     * Starts a run only when none is active (KEEP). Returns the id of the run that is active afterwards,
     * which is another caller's run if it won the race.
     */
    suspend fun enqueueIfIdle(context: Context, songId: Long): UUID {
        val request = request(songId)
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(uniqueName(songId), ExistingWorkPolicy.KEEP, request)
        return activeWorkId(context, songId) ?: request.id
    }

    fun cancel(context: Context, songId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(songId))
    }

    /** Id of the ENQUEUED, RUNNING or BLOCKED run for [songId], if any. */
    suspend fun activeWorkId(context: Context, songId: Long): UUID? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(songId)).first()
            .firstOrNull { isActive(it.state) }?.id

    /** Observes one specific run, never an earlier run that shares the unique name. */
    fun observeById(context: Context, id: UUID): Flow<AnalysisUiState> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(id).map { info ->
            if (info == null) AnalysisUiState(0f, null) else toUiState(info.state, info.progress, info.outputData)
        }

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
