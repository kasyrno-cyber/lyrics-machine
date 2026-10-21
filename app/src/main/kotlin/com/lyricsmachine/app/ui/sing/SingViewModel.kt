package com.lyricsmachine.app.ui.sing

import androidx.lifecycle.ViewModel
import com.lyricsmachine.app.live.LiveEvent
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongMode
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming
import com.lyricsmachine.core.lyrics.LyricLine
import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.playback.PlaybackPosition
import com.lyricsmachine.core.playback.TimingLookup
import com.lyricsmachine.core.tracker.TrackerSnapshot
import com.lyricsmachine.core.tracker.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SingCopy {
    const val MIC_DENIED = "Microphone access is needed to follow you live. Tap to allow."
    const val SWITCHED_TO_TINY = "Switched to a faster model for this device."
    const val STILL_TOO_SLOW = "This device is struggling — the lyrics may lag."
    const val ENGINE_FAILED = "Live tracking stopped working. Tap a line to follow along manually."
    const val MODEL_MISSING = "The speech model isn't downloaded yet."
    const val AUDIO_UNPLAYABLE = "This audio file can't be played. You can sing with the microphone instead."
    const val NO_MODEL = "The speech model isn't downloaded yet. Tap a line or use the arrows to follow along."
    const val NO_TIMINGS = "This song has no timings yet. Tap a line or use the arrows to follow along."
    const val LYRICS_ONLY = "Showing the lyrics only. Tap a line or use the arrows to follow along."
    const val MIC_FAILED = "The microphone couldn't be started. Tap a line to follow along manually."
    const val NOT_FOUND = "This song couldn't be found."
}

fun trackerLabel(state: TrackerState): String = ""

enum class SingMode { PLAYBACK, LIVE, MANUAL }

enum class MessageAction { ALLOW_MIC, USE_MIC }

data class SingUiState(
    val lines: List<LyricLine> = emptyList(),
    val currentLine: Int? = null,
    val currentWord: Int? = null,
    val wordProgress: Float = 0f,
    val mode: SingMode = SingMode.MANUAL,
    val isRunning: Boolean = false,
    val trackerState: TrackerState? = null,
    val message: String? = null,
    val title: String = "",
    val loading: Boolean = true,
    val wordTimed: Boolean = false,
    val messageAction: MessageAction? = null,
    val snackbar: String? = null,
    val requestMic: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
)

interface PlaybackLike {
    val position: StateFlow<PlaybackPosition>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long?>
    val error: StateFlow<String?>
    fun play()
    fun pause()
    fun seekToLine(lineIndex: Int)
    fun close()
}

interface LiveLike {
    val snapshot: StateFlow<TrackerSnapshot>
    val events: SharedFlow<LiveEvent>
    fun start()
    fun stop()
    fun jumpTo(lineIndex: Int)
}

class SingViewModel(
    private val songId: Long,
    private val manual: Boolean,
    private val loadSong: suspend (Long) -> SongEntity?,
    private val songMode: suspend (Long) -> SongMode?,
    private val loadTimings: suspend (Long) -> Pair<List<LineTiming>, List<WordTiming>>,
    private val modelState: suspend (ModelSpec) -> ModelState,
    private val playbackFactory: (String, TimingLookup) -> PlaybackLike,
    private val liveFactory: (Lyrics, Lang?, ModelSpec, CoroutineScope) -> LiveLike,
) : ViewModel() {
    private val _state = MutableStateFlow(SingUiState())
    val state: StateFlow<SingUiState> = _state.asStateFlow()

    fun toggle() {}
    fun nudge(delta: Int) {}
    fun tapLine(index: Int) {}
    fun onMicPermissionResult(granted: Boolean) {}
    fun requestMicAgain() {}
    fun useMicrophone() {}
    fun snackbarShown() {}
    fun dismissMessage() {}
    fun onBackgrounded() {}
}
