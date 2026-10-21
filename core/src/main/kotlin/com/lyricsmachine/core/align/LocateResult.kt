package com.lyricsmachine.core.align

/** endTokenIndex = index of the last matched lyric token + 1 (the "next" position). */
data class LocateResult(val endTokenIndex: Int, val matchedCount: Int, val confidence: Double)
