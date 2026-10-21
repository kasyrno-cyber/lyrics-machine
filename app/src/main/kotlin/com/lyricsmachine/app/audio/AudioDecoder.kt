package com.lyricsmachine.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lyricsmachine.core.audio.AudioMath
import com.lyricsmachine.core.audio.StreamingResampler
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes a song to 16 kHz mono float chunks with MediaExtractor + MediaCodec.
 *
 * Not unit-tested: it needs real media and a hardware/software codec, which neither plain JVM nor Robolectric
 * provides. It is exercised on a device (Task 16).
 */
class AudioDecoder(private val context: Context) {

    /** Duration from the container's KEY_DURATION, or 0 if the container does not say. */
    fun durationMs(uri: Uri): Long {
        val extractor = open(uri)
        try {
            val format = extractor.getTrackFormat(audioTrack(extractor))
            return if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } finally {
            extractor.release()
        }
    }

    /**
     * Lazily emits consecutive chunks of [chunkMs] of 16 kHz mono audio (the last may be shorter), so the whole
     * song is never held in memory. Errors (IOException, IllegalArgumentException, SecurityException,
     * MediaCodec.CodecException, ...) surface while iterating.
     *
     * Cleanup: the codec and extractor are released when iteration completes or throws. If a caller abandons
     * a partly consumed sequence, release must be requested by calling [AutoCloseable.close] on the returned
     * object (AnalysisRunner does); otherwise they leak until GC.
     */
    fun decode16kMono(uri: Uri, chunkMs: Long = 30_000): Sequence<FloatArray> {
        require(chunkMs > 0) { "chunkMs must be positive" }
        val chunkSamples = (chunkMs * AudioMath.TARGET_RATE / 1000).toInt()
        val resources = Resources()
        val seq = sequence {
            try {
                val extractor = open(uri).also { resources.extractor = it }
                val trackIndex = audioTrack(extractor)
                val inFormat = extractor.getTrackFormat(trackIndex)
                extractor.selectTrack(trackIndex)
                val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("no audio mime")
                val codec = MediaCodec.createDecoderByType(mime).also { resources.codec = it }
                codec.configure(inFormat, null, null, 0)
                codec.start()

                var rate = inFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
                var channels = inFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
                var float = false
                val info = MediaCodec.BufferInfo()
                var pending = FloatArray(chunkSamples * 2)
                var pendingSize = 0
                var inputDone = false
                var outputDone = false
                var resampler: StreamingResampler? = null
                var idleAfterEos = 0

                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx) ?: throw IOException("no input buffer")
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        if (++idleAfterEos >= MAX_IDLE_AFTER_EOS) throw IOException("decoder stalled")
                    } else {
                        idleAfterEos = 0
                    }
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate)
                            channels = f.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            float = f.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                        out >= 0 -> {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(out) ?: throw IOException("no output buffer")
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                if (rate <= 0) {
                                    rate = codec.outputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
                                    channels = codec.outputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                                }
                                if (rate <= 0 || channels <= 0) throw IOException("unknown output format")
                                val mono = toMono(buf, float, channels)
                                codec.releaseOutputBuffer(out, false) // data is copied; release before yielding
                                // One resampler per output rate, so fractional position carries across buffers.
                                val rs = resampler?.takeIf { it.fromRate == rate } ?: StreamingResampler(rate).also { resampler = it }
                                val resampled = rs.process(mono)
                                if (pendingSize + resampled.size > pending.size) {
                                    pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + resampled.size))
                                }
                                System.arraycopy(resampled, 0, pending, pendingSize, resampled.size)
                                pendingSize += resampled.size
                                while (pendingSize >= chunkSamples) {
                                    yield(pending.copyOfRange(0, chunkSamples))
                                    System.arraycopy(pending, chunkSamples, pending, 0, pendingSize - chunkSamples)
                                    pendingSize -= chunkSamples
                                }
                            } else {
                                codec.releaseOutputBuffer(out, false)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                if (pendingSize > 0) yield(pending.copyOfRange(0, pendingSize))
            } finally {
                resources.release()
            }
        }
        return CloseableSequence(seq, resources)
    }

    private fun toMono(buf: java.nio.ByteBuffer, float: Boolean, channels: Int): FloatArray {
        buf.order(ByteOrder.nativeOrder())
        if (float) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / channels
            val out = FloatArray(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += fb.get()
                out[f] = sum / channels
            }
            return out
        }
        val sb = buf.asShortBuffer()
        val usable = sb.remaining() / channels * channels
        val shorts = ShortArray(usable)
        sb.get(shorts)
        return AudioMath.toMonoFloat(shorts, channels)
    }

    private fun open(uri: Uri): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Throwable) {
            extractor.release()
            throw e
        }
        return extractor
    }

    private fun audioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        throw IOException("no audio track")
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

    private class Resources {
        @Volatile var extractor: MediaExtractor? = null
        @Volatile var codec: MediaCodec? = null
        private var released = false

        @Synchronized fun release() {
            if (released) return
            released = true
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private class CloseableSequence(private val inner: Sequence<FloatArray>, private val res: Resources) :
        Sequence<FloatArray>, AutoCloseable {
        override fun iterator(): Iterator<FloatArray> = inner.iterator()
        override fun close() = res.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** Consecutive empty output polls after input EOS (5 s at [TIMEOUT_US]) before giving up. */
        const val MAX_IDLE_AFTER_EOS = 500
    }
}
