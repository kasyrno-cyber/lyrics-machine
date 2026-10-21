package com.lyricsmachine.app.speech

import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.speech.HeardWord
import com.lyricsmachine.core.speech.SpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * [SpeechEngine] backed by whisper.cpp. The model is loaded lazily on the first [transcribe] (off Main);
 * one transcription runs at a time. [close] frees the native context and is idempotent.
 */
class WhisperCppEngine(private val modelFile: File, private val threads: Int) : SpeechEngine, Closeable {
    private val mutex = Mutex()
    private var ctx = 0L // guarded by mutex
    @Volatile private var closed = false

    override suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord> {
        check(!closed) { "WhisperCppEngine is closed" }
        val records = withContext(Dispatchers.Default) {
            try {
                mutex.withLock {
                    check(!closed) { "WhisperCppEngine is closed" }
                    if (ctx == 0L) {
                        ctx = WhisperNative.nativeInit(modelFile.absolutePath)
                        check(ctx != 0L) { "failed to load whisper model ${modelFile.name}" }
                    }
                    WhisperNative.nativeTranscribe(ctx, pcm16k, language?.whisperCode, threads)
                }
            } finally {
                freeIfClosedAndIdle() // close() during this call deferred the free to us
            }
        }
        return WhisperWords.parse(records)
    }

    override fun close() {
        closed = true
        // If a transcription holds the lock, it frees the context when it finishes.
        freeIfClosedAndIdle()
    }

    private fun freeIfClosedAndIdle() {
        if (closed && mutex.tryLock()) {
            try {
                freeLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun freeLocked() {
        if (ctx != 0L) {
            WhisperNative.nativeFree(ctx)
            ctx = 0L
        }
    }
}
