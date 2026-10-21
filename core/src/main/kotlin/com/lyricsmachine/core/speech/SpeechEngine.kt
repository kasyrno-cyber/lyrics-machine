package com.lyricsmachine.core.speech

import com.lyricsmachine.core.Lang

interface SpeechEngine {
    /**
     * Transcribes 16 kHz mono float PCM. [language] `null` means auto-detect; callers map
     * [Lang.MIXED] to `null` (its [Lang.whisperCode] is already `null`).
     */
    suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord>
}
