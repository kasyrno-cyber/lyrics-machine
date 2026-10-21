package com.lyricsmachine.app.live

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lyricsmachine.core.audio.AudioMath

/**
 * [MicSource] on [AudioRecord]: MIC source, 16 kHz mono. Prefers ENCODING_PCM_FLOAT and falls back to
 * PCM_16BIT (converted to floats) when float capture cannot be initialised. Reads ~[blockMs] blocks in
 * READ_BLOCKING mode on a dedicated thread and delivers each block to `onSamples` on that thread.
 *
 * Requires RECORD_AUDIO; the caller checks the permission before [start]. [start] throws
 * [IllegalStateException] when the recorder cannot be initialised. [stop] releases the recorder and is
 * idempotent; one instance can be started again after [stop].
 */
class MicCapture(
    private val sampleRate: Int = AudioMath.TARGET_RATE,
    private val blockMs: Int = 100,
) : MicSource {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    override fun start(onSamples: (FloatArray) -> Unit) {
        synchronized(lock) {
            check(record == null) { "MicCapture already started" }
            val (rec, isFloat) = createFloat()?.let { it to true }
                ?: createPcm16()?.let { it to false }
                ?: throw IllegalStateException("AudioRecord could not be initialised")
            try {
                rec.startRecording()
            } catch (e: Exception) {
                rec.release()
                throw IllegalStateException("AudioRecord could not start recording", e)
            }
            record = rec
            running = true
            val blockSamples = maxOf(1, sampleRate * blockMs / 1000)
            thread = Thread({ readLoop(rec, isFloat, blockSamples, onSamples) }, "lyrics-mic").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        }
    }

    override fun stop() {
        val rec: AudioRecord
        val t: Thread?
        synchronized(lock) {
            rec = record ?: return
            t = thread
            record = null
            thread = null
            running = false
        }
        try { rec.stop() } catch (_: IllegalStateException) {} // unblocks a pending blocking read
        try { t?.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        rec.release()
    }

    private fun readLoop(rec: AudioRecord, isFloat: Boolean, blockSamples: Int, onSamples: (FloatArray) -> Unit) {
        val floats = FloatArray(blockSamples)
        val shorts = if (isFloat) null else ShortArray(blockSamples)
        while (running) {
            val n = if (shorts == null) {
                rec.read(floats, 0, blockSamples, AudioRecord.READ_BLOCKING)
            } else {
                rec.read(shorts, 0, blockSamples, AudioRecord.READ_BLOCKING).also { read ->
                    for (i in 0 until read) floats[i] = shorts[i] / 32768f
                }
            }
            if (n < 0) break // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT etc.
            if (n > 0 && running) onSamples(floats.copyOf(n))
        }
    }

    @SuppressLint("MissingPermission")
    private fun createFloat(): AudioRecord? = create(AudioFormat.ENCODING_PCM_FLOAT)

    @SuppressLint("MissingPermission")
    private fun createPcm16(): AudioRecord? = create(AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission")
    private fun create(encoding: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (min <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding, min * 2)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
