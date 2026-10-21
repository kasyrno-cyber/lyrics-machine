package com.lyricsmachine.app.ui.sing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsmachine.app.analysis.chooseModel
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
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.playback.PlaybackPosition
import com.lyricsmachine.core.playback.TimingLookup
import com.lyricsmachine.core.tracker.TrackerSnapshot
import com.lyricsmachine.core.tracker.TrackerState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** Text of the tracker state chip shown while listening live. */
fun trackerLabel(state: TrackerState): String = when (state) {
    TrackerState.Listening -> "Listening"
    TrackerState.Locked, TrackerState.Coasting -> "Following"
    TrackerState.Paused -> "Paused"
    TrackerState.Lost -> "Lost — tap a line to help"
}

enum class SingMode { PLAYBACK, LIVE, MANUAL }

enum class MessageAction { ALLOW_MIC, USE_MIC }

/** One queued snackbar; [id] is unique per view model, so the same [text] twice is still two notices. */
data class Notice(val id: Long, val text: String)

/**
 * What the Sing screen shows. [currentWord]/[wordProgress] fill the current line word by word when
 * [wordTimed]; otherwise the whole current line is highlighted. [message] is a persistent notice (with an
 * optional [messageAction]); [snackbar] is the head of a queue of one-off notices, acknowledged by id with
 * [SingViewModel.snackbarShown]. [canToggle] is false once playback has failed (no dead Play button).
 * [sentToSettings] is true while the user is in app settings after tapping the mic-denied notice. [requestMic] asks the screen to check/request RECORD_AUDIO and report back
 * through [SingViewModel.onMicPermissionResult].
 */
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
    val snackbar: Notice? = null,
    val requestMic: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val sentToSettings: Boolean = false,
    val canToggle: Boolean = true,
)

/** What the view model needs from [com.lyricsmachine.app.playback.PlaybackSync]; faked in tests. */
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

/** What the view model needs from [com.lyricsmachine.app.live.LiveSession]; faked in tests. */
interface LiveLike {
    val snapshot: StateFlow<TrackerSnapshot>
    val events: SharedFlow<LiveEvent>
    fun start()
    fun stop()
    fun jumpTo(lineIndex: Int)
}

/**
 * Drives the Sing screen in one of three modes:
 * - PLAYBACK (AUDIO_READY with timings): [PlaybackLike] plays the audio and its position picks the line.
 * - LIVE (LYRICS_ONLY / AUDIO_NOT_ANALYSED): a [LiveLike] session follows the microphone.
 * - MANUAL (`manual` nav arg, no model, mic denied, no timings, engine failure): taps and nudges only.
 *
 * In LIVE, listening starts as soon as the mic permission is granted on entry; in PLAYBACK nothing plays
 * until the user taps Play. Nothing resumes after backgrounding. Live sessions cannot be restarted, so every Listen
 * creates a new one. Each [LiveEvent] kind is shown at most once per session even if replayed.
 */
class SingViewModel(
    private val songId: Long,
    private val manual: Boolean,
    private val loadSong: suspend (Long) -> SongEntity?,
    private val songMode: suspend (Long) -> SongMode?,
    private val loadTimings: suspend (Long) -> Pair<List<LineTiming>, List<WordTiming>>,
    private val modelState: suspend (ModelSpec) -> ModelState,
    /** Called on Main with the song's audio URI. */
    private val playbackFactory: (String, TimingLookup) -> PlaybackLike,
    private val liveFactory: (Lyrics, Lang?, ModelSpec, CoroutineScope) -> LiveLike,
) : ViewModel() {
    private val _state = MutableStateFlow(SingUiState())
    val state: StateFlow<SingUiState> = _state.asStateFlow()

    private var lyrics = Lyrics(emptyList())
    private var language: Lang? = null
    private var lyricLines: List<Int> = emptyList()

    private var playback: PlaybackLike? = null
    private var playbackJob: Job? = null
    private var playbackErrorShown = false

    private var live: LiveLike? = null
    private var liveJob: Job? = null
    private var liveSpec: ModelSpec? = null
    private val handledEvents = mutableSetOf<LiveEvent>()
    private val snackbars = ArrayDeque<Notice>()
    private var nextNoticeId = 0L

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val song = safely { loadSong(songId) }
        if (song == null) {
            _state.update { it.copy(loading = false, message = SingCopy.NOT_FOUND) }
            return
        }
        lyrics = LyricsParser.parse(song.lyricsText)
        language = song.language
        lyricLines = lyrics.lines.filter { !it.isSpacer }.map { it.index }
        _state.update {
            it.copy(title = song.title, lines = lyrics.lines, loading = false, currentLine = lyricLines.firstOrNull())
        }
        if (manual) {
            enterManual(SingCopy.LYRICS_ONLY)
            return
        }
        val uri = song.audioUri
        if (safely { songMode(songId) } == SongMode.AUDIO_READY && uri != null) enterPlayback(uri) else enterLive()
    }

    private suspend fun enterPlayback(uri: String) {
        val (lines, words) = safely { loadTimings(songId) } ?: (emptyList<LineTiming>() to emptyList())
        if (lines.isEmpty()) {
            enterManual(SingCopy.NO_TIMINGS)
            return
        }
        val pb = try {
            playbackFactory(uri, TimingLookup(lines, words))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            enterManual(SingCopy.AUDIO_UNPLAYABLE, MessageAction.USE_MIC)
            return
        }
        playback = pb
        playbackErrorShown = false
        val wordTimed = words.isNotEmpty()
        _state.update { it.copy(mode = SingMode.PLAYBACK, wordTimed = wordTimed, isRunning = false, canToggle = true) }
        playbackJob = viewModelScope.launch {
            combine(pb.position, pb.isPlaying, pb.positionMs, pb.durationMs, pb.error) { p, playing, ms, dur, err ->
                PlaybackFrame(p, playing, ms, dur, err)
            }.collect { f -> applyPlayback(f, wordTimed) }
        }
    }

    private class PlaybackFrame(
        val position: PlaybackPosition, val playing: Boolean, val ms: Long, val duration: Long?, val error: String?,
    )

    private fun applyPlayback(f: PlaybackFrame, wordTimed: Boolean) {
        _state.update {
            if (it.mode != SingMode.PLAYBACK) return@update it
            it.copy(
                currentLine = f.position.lineIndex,
                currentWord = f.position.wordIndex,
                wordProgress = f.position.wordProgress,
                wordTimed = wordTimed,
                isRunning = f.playing && f.error == null,
                positionMs = f.ms,
                durationMs = f.duration,
            )
        }
        if (f.error != null && !playbackErrorShown) {
            // Never show the player's own text: it is technical and not translated.
            playbackErrorShown = true
            _state.update {
                it.copy(message = SingCopy.AUDIO_UNPLAYABLE, messageAction = MessageAction.USE_MIC, canToggle = false)
            }
        }
    }

    /** Picks the model and asks the screen for the mic permission; MANUAL when no model is ready. */
    private suspend fun enterLive() {
        val chosen = safely { chooseModel(modelState) }
        if (chosen == null) {
            enterManual(SingCopy.NO_MODEL)
            return
        }
        liveSpec = chosen.first
        _state.update {
            it.copy(mode = SingMode.LIVE, isRunning = false, requestMic = true, message = null, messageAction = null)
        }
    }

    private fun enterManual(message: String?, action: MessageAction? = null) {
        _state.update {
            it.copy(
                mode = SingMode.MANUAL,
                isRunning = false,
                trackerState = null,
                wordTimed = false,
                currentWord = null,
                wordProgress = 0f,
                requestMic = false,
                currentLine = it.currentLine ?: lyricLines.firstOrNull(),
                message = message ?: it.message,
                messageAction = if (message != null) action else it.messageAction,
            )
        }
    }

    /** The screen's answer to [SingUiState.requestMic] (or to a retry after [requestMicAgain]). */
    fun onMicPermissionResult(granted: Boolean) {
        _state.update { it.copy(requestMic = false) }
        if (!granted) {
            stopLive()
            enterManual(SingCopy.MIC_DENIED, MessageAction.ALLOW_MIC)
            return
        }
        if (liveSpec == null) {
            viewModelScope.launch { enterLive() }
            return
        }
        startLive()
    }

    /** The user tapped the mic-denied notice: ask for the permission again. */
    fun requestMicAgain() {
        if (liveSpec == null) return
        _state.update { it.copy(requestMic = true) }
    }

    private fun startLive() {
        val spec = liveSpec ?: return
        stopLive()
        val resumeLine = _state.value.currentLine
        val session = try {
            liveFactory(lyrics, language, spec, viewModelScope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            enterManual(SingCopy.MIC_FAILED)
            return
        }
        try {
            session.start()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            try { session.stop() } catch (_: Exception) {}
            enterManual(SingCopy.MIC_FAILED)
            return
        }
        live = session
        handledEvents.clear()
        _state.update {
            it.copy(
                mode = SingMode.LIVE, isRunning = true, message = null, messageAction = null, requestMic = false,
                canToggle = true,
            )
        }
        liveJob = viewModelScope.launch {
            launch { session.snapshot.collect { applySnapshot(session, it) } }
            launch { session.events.collect { onLiveEvent(session, it) } }
        }
        if (resumeLine != null && resumeLine != lyricLines.firstOrNull()) {
            session.jumpTo(resumeLine)
            _state.update { it.copy(currentLine = resumeLine, currentWord = null, wordProgress = 0f) }
        }
    }

    private fun applySnapshot(session: LiveLike, snap: TrackerSnapshot) {
        if (session !== live) return
        val advancing = snap.state == TrackerState.Locked || snap.state == TrackerState.Coasting
        var word: Int? = null
        var progress = 0f
        val tokens = lyrics.tokens
        if (advancing && tokens.isNotEmpty()) {
            val pos = snap.tokenPosition
            val idx = floor(pos).toInt().coerceIn(0, tokens.lastIndex)
            val ref = tokens[idx]
            if (ref.lineIndex == snap.lineIndex) {
                word = ref.wordIndex
                progress = if (pos >= tokens.size) 1f else (pos - floor(pos)).toFloat().coerceIn(0f, 1f)
            }
        }
        _state.update {
            it.copy(
                currentLine = snap.lineIndex,
                currentWord = word,
                wordProgress = progress,
                wordTimed = advancing,
                trackerState = snap.state,
            )
        }
    }

    private fun onLiveEvent(session: LiveLike, event: LiveEvent) {
        if (session !== live || !handledEvents.add(event)) return
        when (event) {
            LiveEvent.SwitchedToTinyModel -> {
                liveSpec = ModelSpec.TINY // a restarted session should not try base again
                showSnackbar(SingCopy.SWITCHED_TO_TINY)
            }
            LiveEvent.StillTooSlow -> showSnackbar(SingCopy.STILL_TOO_SLOW)
            LiveEvent.EngineFailed -> {
                showSnackbar(SingCopy.ENGINE_FAILED)
                stopLive()
                enterManual(null)
            }
        }
    }

    private fun stopLive() {
        val session = live ?: return
        live = null
        liveJob?.cancel()
        liveJob = null
        try { session.stop() } catch (_: Exception) {}
        _state.update { it.copy(isRunning = false, trackerState = null, wordTimed = false, currentWord = null) }
    }

    private fun closePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        val pb = playback ?: return
        playback = null
        try { pb.close() } catch (_: Exception) {}
        _state.update { it.copy(isRunning = false, positionMs = 0L, durationMs = null) }
    }

    /** PLAYBACK: play/pause. LIVE: start (via the permission check, with a new session) or stop. MANUAL: no-op. */
    fun toggle() {
        if (!_state.value.canToggle) return
        when (_state.value.mode) {
            SingMode.PLAYBACK -> {
                val pb = playback ?: return
                if (pb.isPlaying.value) pb.pause() else pb.play()
            }
            SingMode.LIVE -> if (live != null) stopLive() else _state.update { it.copy(requestMic = true) }
            SingMode.MANUAL -> Unit
        }
    }

    /** Moves [delta] lyric lines, skipping spacers and clamping to the first and last lyric line. */
    fun nudge(delta: Int) {
        if (lyricLines.isEmpty() || delta == 0) return
        var line = _state.value.currentLine
        repeat(kotlin.math.abs(delta)) {
            val c = line
            line = when {
                c == null -> lyricLines.first()
                delta > 0 -> lyricLines.firstOrNull { it > c } ?: lyricLines.last()
                else -> lyricLines.lastOrNull { it < c } ?: lyricLines.first()
            }
        }
        line?.let { moveTo(it) }
    }

    /** Moves to [index]; a spacer moves to the next lyric line (or the last one when none follows). */
    fun tapLine(index: Int) {
        if (lyricLines.isEmpty()) return
        moveTo(lyricLines.firstOrNull { it >= index } ?: lyricLines.last())
    }

    private fun moveTo(line: Int) {
        when (_state.value.mode) {
            SingMode.PLAYBACK -> if (_state.value.canToggle) playback?.seekToLine(line) // errored: like MANUAL
            SingMode.LIVE -> live?.jumpTo(line)
            SingMode.MANUAL -> Unit
        }
        _state.update { it.copy(currentLine = line, currentWord = null, wordProgress = 0f) }
    }

    /** "Use microphone instead": stops playback and starts LIVE, or explains that no model is downloaded. */
    fun useMicrophone() {
        val s = _state.value
        if (s.mode == SingMode.LIVE) return
        if (s.mode == SingMode.MANUAL && s.messageAction != MessageAction.USE_MIC) return
        viewModelScope.launch {
            val chosen = safely { chooseModel(modelState) }
            if (chosen == null) {
                _state.update { it.copy(message = SingCopy.MODEL_MISSING, messageAction = null) }
                return@launch
            }
            liveSpec = chosen.first
            closePlayback()
            _state.update {
                it.copy(
                    mode = SingMode.LIVE, isRunning = false, requestMic = true, message = null, messageAction = null,
                    wordTimed = false, currentWord = null, canToggle = true,
                )
            }
        }
    }

    /** Removes the notice with [id]; a repeated or stale acknowledgement changes nothing. */
    fun snackbarShown(id: Long) {
        snackbars.removeAll { it.id == id }
        _state.update { it.copy(snackbar = snackbars.firstOrNull()) }
    }

    /** The mic-denied notice sent the user to app settings ("don't ask again" was chosen). */
    fun openedSettingsForMic() {
        _state.update { it.copy(sentToSettings = true) }
    }

    /** The screen is visible again; starts listening only if we sent the user to settings and they allowed the mic. */
    fun onReturnedFromSettings(granted: Boolean) {
        if (!_state.value.sentToSettings) return
        _state.update { it.copy(sentToSettings = false) }
        if (granted) onMicPermissionResult(true)
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null, messageAction = null) }
    }

    /** ON_STOP: pause playback and stop listening; nothing resumes until the user taps again. */
    fun onBackgrounded() {
        playback?.pause()
        stopLive()
    }

    override fun onCleared() {
        stopLive()
        closePlayback()
    }

    private fun showSnackbar(text: String) {
        snackbars.addLast(Notice(nextNoticeId++, text))
        _state.update { it.copy(snackbar = snackbars.first()) }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
