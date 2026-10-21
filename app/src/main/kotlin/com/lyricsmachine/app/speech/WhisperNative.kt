package com.lyricsmachine.app.speech

/** JNI bindings to `liblyricsjni.so` (src/main/cpp/whisper_jni.cpp). Not thread-safe per context. */
internal object WhisperNative {
    init {
        System.loadLibrary("lyricsjni")
    }

    /** Loads a ggml model; returns 0 on failure. */
    external fun nativeInit(path: String): Long

    /** Transcribes 16 kHz mono PCM; [lang] null means auto-detect. Returns one `"t0Ms|t1Ms|p|text"` per word. */
    external fun nativeTranscribe(ctx: Long, pcm: FloatArray, lang: String?, threads: Int): Array<String>

    external fun nativeFree(ctx: Long)
}
