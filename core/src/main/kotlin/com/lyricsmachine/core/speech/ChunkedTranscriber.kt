package com.lyricsmachine.core.speech

import com.lyricsmachine.core.Lang
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Feeds consecutive 16 kHz chunks to [engine] and shifts each word by its chunk's start time.
 * [chunkMs] is the nominal chunk size the decoder is expected to produce; offsets are computed
 * from the actual chunk lengths, so a short final chunk is handled correctly.
 */
class ChunkedTranscriber(private val engine: SpeechEngine, val chunkMs: Long = 30_000) {
    suspend fun transcribe(
        chunks: Sequence<FloatArray>,
        totalSamples: Long,
        language: Lang?,
        onProgress: (Float) -> Unit,
    ): List<HeardWord> {
        val out = ArrayList<HeardWord>()
        var consumed = 0L
        var lastReported = -1f
        for (chunk in chunks) {
            coroutineContext.ensureActive()
            val offsetMs = consumed * 1000L / SAMPLE_RATE
            for (w in engine.transcribe(chunk, language)) {
                out += w.copy(startMs = w.startMs + offsetMs, endMs = w.endMs + offsetMs)
            }
            consumed += chunk.size
            if (totalSamples > 0) {
                lastReported = (consumed.toDouble() / totalSamples).toFloat().coerceIn(0f, 1f)
                onProgress(lastReported)
            }
        }
        if (lastReported != 1f) onProgress(1f)
        return out
    }

    private companion object { const val SAMPLE_RATE = 16_000L }
}
