package com.lyricsmachine.core.speech

/** A word recognised by the speech model, with its time span in the song. */
data class HeardWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
