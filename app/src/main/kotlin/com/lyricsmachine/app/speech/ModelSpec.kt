package com.lyricsmachine.app.speech

/**
 * Whisper models downloaded on first launch. [sha256] and [sizeBytes] are pinned from the
 * Hugging Face API (`/api/models/ggerganov/whisper.cpp/tree/main`, fields `lfs.oid` / `lfs.size`).
 */
enum class ModelSpec(val fileName: String, val url: String, val sha256: String, val sizeBytes: Long) {
    BASE(
        "ggml-base-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        59_707_625L,
    ),
    TINY(
        "ggml-tiny-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        32_152_673L,
    ),
    ;

    val source: ModelSource get() = ModelSource(fileName, url, sha256, sizeBytes)
}

/** Where and how to fetch one model file; separate from [ModelSpec] so tests can point at a fake server. */
data class ModelSource(val fileName: String, val url: String, val sha256: String, val sizeBytes: Long)
