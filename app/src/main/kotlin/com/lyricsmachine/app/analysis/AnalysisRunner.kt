package com.lyricsmachine.app.analysis

import com.lyricsmachine.app.store.SongRepository
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.align.Aligner
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.speech.ChunkedTranscriber
import com.lyricsmachine.core.speech.SpeechEngine
import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException

sealed interface AnalysisResult {
    data class Done(val matchRatio: Double, val lowQuality: Boolean) : AnalysisResult
    data class Error(val reason: AnalysisError) : AnalysisResult
}

enum class AnalysisError { AUDIO_UNREADABLE, MODEL_MISSING, CANCELLED, ENGINE_FAILED }

/** The engine chosen for a run and the model file name recorded with the saved analysis. */
data class EngineChoice(val engine: SpeechEngine, val modelName: String)

/**
 * Mode 1 pipeline: load song, parse lyrics, transcribe the decoded audio in chunks, align, save.
 *
 * [engineProvider] returns null when no model is Ready (-> MODEL_MISSING); it is called only after the song
 * has been found, so a native model is not loaded for a run that cannot proceed. An engine that is
 * [Closeable] is closed when the run ends. A [decode] result that is [AutoCloseable] is closed when the
 * run ends (also on cancellation), so codec resources do not wait for GC.
 *
 * Nothing is written unless the result is [AnalysisResult.Done]; the song itself is never modified.
 * [CancellationException] is rethrown (the worker maps it to CANCELLED).
 */
class AnalysisRunner(
    private val repo: SongRepository,
    private val engineProvider: suspend () -> EngineChoice?,
    private val decode: (String) -> Sequence<FloatArray>,
    private val totalSamples: (String) -> Long,
) {
    /** Marks an exception that came from decoding, to tell it apart from engine failures. */
    private class DecodeFailure(cause: Throwable) : Exception(cause)

    suspend fun run(songId: Long, onProgress: (Float) -> Unit): AnalysisResult {
        val song = repo.get(songId) ?: return AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE)
        val uri = song.audioUri ?: return AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE)
        val lyrics = LyricsParser.parse(song.lyricsText)
        val choice = engineProvider() ?: return AnalysisResult.Error(AnalysisError.MODEL_MISSING)

        var source: Sequence<FloatArray>? = null
        try {
            val total = try {
                totalSamples(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE)
            }
            val chunks = try {
                decode(uri).also { source = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE)
            }
            val language = if (song.language == Lang.MIXED) null else song.language
            val heard = try {
                ChunkedTranscriber(choice.engine).transcribe(wrap(chunks), total, language, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecodeFailure) {
                return AnalysisResult.Error(AnalysisError.AUDIO_UNREADABLE)
            } catch (e: Exception) {
                return AnalysisResult.Error(AnalysisError.ENGINE_FAILED)
            }
            val alignment = Aligner.alignFull(lyrics, heard)
            repo.saveAlignment(songId, alignment, choice.modelName)
            return AnalysisResult.Done(alignment.matchRatio, alignment.isLowQuality)
        } finally {
            try { (source as? AutoCloseable)?.close() } catch (_: Exception) {}
            try { (choice.engine as? Closeable)?.close() } catch (_: Exception) {}
        }
    }

    private fun wrap(src: Sequence<FloatArray>): Sequence<FloatArray> = Sequence {
        val it = try {
            src.iterator()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DecodeFailure(e)
        }
        object : Iterator<FloatArray> {
            override fun hasNext() = guard { it.hasNext() }
            override fun next() = guard { it.next() }
            private fun <T> guard(block: () -> T): T = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DecodeFailure(e)
            }
        }
    }
}
