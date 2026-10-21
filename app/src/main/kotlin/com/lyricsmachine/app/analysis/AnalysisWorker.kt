package com.lyricsmachine.app.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lyricsmachine.app.AppContainer
import com.lyricsmachine.app.LyricsMachineApp
import com.lyricsmachine.app.audio.AudioDecoder
import com.lyricsmachine.app.speech.ModelManager
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [AnalysisRunner] as a foreground (dataSync) job. Error results are returned as `Result.success`
 * with `error` in the output so the UI can read them; only an escaping exception makes the work FAILED.
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val songId = inputData.getLong(KEY_SONG_ID, -1L)
        if (songId < 0) return Result.failure()
        val container: AppContainer = (applicationContext as LyricsMachineApp).container
        val title = container.songRepository.get(songId)?.title ?: ""
        try {
            setForeground(foregroundInfo(title))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Foreground start can be refused (e.g. background-start limits); analysis still runs best-effort.
        }

        val decoder = AudioDecoder(applicationContext)
        val runner = AnalysisRunner(
            repo = container.songRepository,
            engineProvider = {
                chooseModel(container.modelManager)?.let { (spec, file) ->
                    EngineChoice(container.createSpeechEngine(file), spec.fileName)
                }
            },
            decode = { decoder.decode16kMono(Uri.parse(it)) },
            totalSamples = { decoder.durationMs(Uri.parse(it)) * 16 },
        )

        val result = coroutineScope {
            val progress = Channel<Float>(Channel.CONFLATED)
            val reporter = launch { for (p in progress) setProgress(workDataOf(KEY_PROGRESS to p)) }
            try {
                runner.run(songId) { progress.trySend(it) }
            } finally {
                progress.close()
                if (!reporter.isCancelled) reporter.join()
            }
        }
        return Result.success(outputOf(result))
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Analysis", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Analysing $title…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val id = NOTIFICATION_ID
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_SONG_ID = "songId"
        const val KEY_PROGRESS = "progress"
        const val KEY_MATCH_RATIO = "matchRatio"
        const val KEY_LOW_QUALITY = "lowQuality"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 4711

        internal fun outputOf(result: AnalysisResult): Data = when (result) {
            is AnalysisResult.Done -> workDataOf(KEY_MATCH_RATIO to result.matchRatio, KEY_LOW_QUALITY to result.lowQuality)
            is AnalysisResult.Error -> workDataOf(KEY_ERROR to result.reason.name)
        }

        private const val MODEL_SETTLE_MS = 1_500L

        /**
         * BASE if Ready, else TINY if Ready, else null. [ModelManager] verifies files already on disk asynchronously
         * after the first [ModelManager.state] call, so a state still `Missing` is given a moment to settle.
         */
        internal suspend fun chooseModel(manager: ModelManager): Pair<ModelSpec, java.io.File>? {
            for (spec in listOf(ModelSpec.BASE, ModelSpec.TINY)) {
                val flow = manager.state(spec)
                val state = withTimeoutOrNull(MODEL_SETTLE_MS) { flow.first { it !is ModelState.Missing } } ?: flow.value
                if (state is ModelState.Ready) return spec to state.file
            }
            return null
        }
    }
}
