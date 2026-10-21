package com.lyricsmachine.core.tracker

data class TrackerConfig(
    val chunkIntervalMs: Long = 2_000, val windowMs: Long = 4_000,
    val windowLinesBack: Int = 1, val windowLinesAhead: Int = 4,
    val silencePauseMs: Long = 4_000, val lostAfterMs: Long = 10_000,
    val initialPaceTokensPerSec: Double = 2.0, val paceSmoothing: Double = 0.3,
    val minPace: Double = 0.5, val maxPace: Double = 6.0,
    val minLockTokens: Int = 3, val lockConfidence: Double = 0.6,
    val maxCoastLines: Int = 2,
)

enum class TrackerState { Listening, Locked, Coasting, Paused, Lost }

data class TrackerSnapshot(val state: TrackerState, val tokenPosition: Double, val lineIndex: Int)
