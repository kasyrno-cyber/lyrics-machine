package com.lyricsmachine.app.live

import android.util.Log
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.audio.AudioMath
import com.lyricsmachine.core.audio.RollingBuffer
import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.speech.HeardWord
import com.lyricsmachine.core.speech.PerformanceGuard
import com.lyricsmachine.core.speech.SpeechEngine
import com.lyricsmachine.core.tracker.LiveTracker
import com.lyricsmachine.core.tracker.TrackerConfig
import com.lyricsmachine.core.tracker.TrackerSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException

/** 16 kHz mono float microphone samples, delivered on the source's own thread. */
interface MicSource {
    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}

sealed interface LiveEvent {
    data object SwitchedToTinyModel : LiveEvent
    data object StillTooSlow : LiveEvent
    /** The engine keeps failing and no fallback is left; live mode cannot hear anything. */
    data object EngineFailed : LiveEvent
}

/**
 * Mode 2: follows the room through the microphone. Every `chunkIntervalMs` the last `windowMs` of audio is
 * transcribed (unless silent) and fed to a [LiveTracker]; [snapshot] is published after each chunk and by a
 * ~100 ms ticker so extrapolation is visible between chunks.
 *
 * Threading: every [LiveTracker] and [RollingBuffer] call runs on [trackerDispatcher] (single-threaded;
 * production uses `Dispatchers.Default.limitedParallelism(1)`). Mic samples are handed over through a channel.
 * Transcription runs in [scope] and the engine picks its own dispatcher; at most one runs at a time, and a
 * tick that arrives while one is still running is skipped. A chunk is stamped with the clock at the moment
 * its window was taken, not when inference finished, so the tracker's extrapolation absorbs the latency.
 * A chunk whose window was taken before a [jumpTo] is discarded.
 *
 * The session starts on [initialModel] (callers pass BASE when it is Ready, else TINY). When the median
 * inference time exceeds the chunk interval on BASE it switches to TINY (closing BASE once its in-flight
 * transcription is done) and emits [LiveEvent.SwitchedToTinyModel]; on TINY it emits
 * [LiveEvent.StillTooSlow] once. Engines that are [Closeable] are closed by the session.
 *
 * Engine failures (exceptions, including a CancellationException while the session is still active) are
 * logged through [log] and counted; a success resets the count. After 3 in a row on
 * BASE the session switches to TINY as above; after that many on TINY, or when the switch itself fails, it
 * emits [LiveEvent.EngineFailed] once. [events] replays up to 3 events (each kind is emitted at most once),
 * so a late collector still sees them.
 *
 * Lifecycle: [start] once, then [stop] (idempotent; stops the mic, the ticker and the chunk loop, and closes
 * the engine after any in-flight transcription completes). A stopped session cannot be restarted: [start]
 * after [stop] throws [IllegalStateException]; create a new session instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSession(
    lyrics: Lyrics,
    private val mic: MicSource,
    private val engineFor: (ModelSpec) -> SpeechEngine,
    language: Lang?,
    private val clock: () -> Long,
    scope: CoroutineScope,
    private val config: TrackerConfig = TrackerConfig(),
    private val trackerDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val initialModel: ModelSpec = ModelSpec.BASE,
    private val tickerIntervalMs: Long = 100,
    private val log: (String, Throwable) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) {
    private val language: Lang? = if (language == Lang.MIXED) null else language
    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // Confined to trackerDispatcher.
    private val tracker = LiveTracker(lyrics, config)
    private val buffer = RollingBuffer((config.windowMs * AudioMath.TARGET_RATE / 1000).toInt())
    private var guard = PerformanceGuard(budgetMs = config.chunkIntervalMs)
    private var busy = false
    private var jumpGeneration = 0
    private var stillTooSlowSent = false
    private var switchFailed = false
    private var consecutiveFailures = 0
    private var engineFailedSent = false

    // Engine lifecycle; guarded by lock because stop() may run on another thread.
    private val lock = Any()
    private var engine: SpeechEngine? = null
    private var spec: ModelSpec = initialModel
    private var inFlight: Job? = null
    private var started = false
    private var stopped = false

    private val samples = Channel<FloatArray>(Channel.UNLIMITED)

    private val _snapshot = MutableStateFlow(tracker.snapshot(clock()))
    val snapshot: StateFlow<TrackerSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(replay = 3)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    /** Starts the mic, the chunk loop and the snapshot ticker. Throws [IllegalStateException] after [stop]. */
    fun start() {
        synchronized(lock) {
            check(!stopped) { "LiveSession cannot be restarted after stop(); create a new session" }
            if (started) return
            started = true
            engine = engineFor(initialModel)
            spec = initialModel
        }
        sessionScope.launch(trackerDispatcher) {
            for (block in samples) buffer.append(block)
        }
        sessionScope.launch(trackerDispatcher) {
            while (isActive) {
                delay(config.chunkIntervalMs)
                tick()
            }
        }
        sessionScope.launch(trackerDispatcher) {
            while (isActive) {
                delay(tickerIntervalMs)
                _snapshot.value = tracker.snapshot(clock())
            }
        }
        // Mic callbacks arrive on the mic thread; only hand the samples over, never touch the buffer here.
        mic.start { block -> samples.trySend(block.copyOf()) }
    }

    /** Moves the tracker to [lineIndex] (a user tap). Ignored after [stop]. */
    fun jumpTo(lineIndex: Int) {
        sessionScope.launch(trackerDispatcher) {
            jumpGeneration++
            _snapshot.value = tracker.onUserJump(clock(), lineIndex)
        }
    }

    /** Stops the mic, the loops and closes the engine (after an in-flight transcription). Idempotent. */
    fun stop() {
        val toClose: SpeechEngine?
        val running: Job?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            toClose = engine
            engine = null
            running = inFlight
        }
        try {
            mic.stop()
        } finally {
            samples.close()
            sessionScope.cancel()
            if (running == null) closeEngine(toClose)
            else running.invokeOnCompletion { closeEngine(toClose) }
        }
    }

    /** Runs on [trackerDispatcher]. */
    private fun tick() {
        if (busy) return // previous transcription still running: skip this tick
        val window = buffer.latest()
        val windowEndMs = clock()
        if (AudioMath.rms(window) <= AudioMath.SILENCE_RMS) {
            tracker.onChunk(windowEndMs, emptyList(), audioPresent = false)
            _snapshot.value = tracker.snapshot(clock())
            return
        }
        val generation = jumpGeneration
        synchronized(lock) {
            if (stopped) return
            val current = engine ?: return
            val usedSpec = spec
            busy = true
            inFlight = sessionScope.launch {
                try {
                    val startMs = clock()
                    var failure: Throwable? = null
                    val heard: List<HeardWord> = try {
                        current.transcribe(window, language)
                    } catch (e: CancellationException) {
                        if (!sessionScope.isActive) throw e // the session is stopping
                        failure = e // a stray cancellation from inside the engine counts as a failure
                        emptyList()
                    } catch (e: Exception) {
                        failure = e
                        emptyList()
                    }
                    val inferenceMs = clock() - startMs
                    withContext(trackerDispatcher) {
                        busy = false
                        if (generation == jumpGeneration) {
                            tracker.onChunk(windowEndMs, heard, audioPresent = true)
                            _snapshot.value = tracker.snapshot(clock())
                        }
                        if (failure != null) {
                            log("transcription failed on $usedSpec", failure)
                            onEngineFailure(usedSpec)
                        } else {
                            consecutiveFailures = 0
                            guard.record(inferenceMs)
                            if (guard.overBudget) onOverBudget(usedSpec)
                        }
                    }
                } finally {
                    withContext(NonCancellable + trackerDispatcher) { busy = false }
                }
            }
        }
    }

    /** Runs on [trackerDispatcher] after a failed transcription. */
    private fun onEngineFailure(usedSpec: ModelSpec) {
        consecutiveFailures++
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return
        if (usedSpec == ModelSpec.TINY || switchFailed || !switchToTiny()) {
            switchFailed = true
            reportOnce(LiveEvent.EngineFailed)
        }
    }

    /** Runs on [trackerDispatcher] after a transcription over budget. */
    private fun onOverBudget(usedSpec: ModelSpec) {
        if (usedSpec == ModelSpec.TINY || switchFailed || !switchToTiny()) {
            switchFailed = switchFailed || usedSpec != ModelSpec.TINY // no tiny model: stay on base
            reportOnce(LiveEvent.StillTooSlow)
        }
    }

    /**
     * Runs on [trackerDispatcher], inside the transcription job that just finished, so the old engine is idle.
     * Returns false when the tiny engine cannot be created (the session stays on base).
     */
    private fun switchToTiny(): Boolean {
        val tinyEngine = try {
            engineFor(ModelSpec.TINY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cannot create the tiny engine", e)
            return false
        }
        val old: SpeechEngine?
        synchronized(lock) {
            if (stopped) {
                old = null
            } else {
                old = engine
                engine = tinyEngine
                spec = ModelSpec.TINY
            }
        }
        if (old == null) {
            closeEngine(tinyEngine) // stopped meanwhile; stop() closes the base engine
            return true
        }
        closeEngine(old)
        guard = PerformanceGuard(budgetMs = config.chunkIntervalMs)
        consecutiveFailures = 0
        _events.tryEmit(LiveEvent.SwitchedToTinyModel)
        return true
    }

    private fun reportOnce(event: LiveEvent) {
        when (event) {
            LiveEvent.StillTooSlow -> if (stillTooSlowSent) return else stillTooSlowSent = true
            LiveEvent.EngineFailed -> if (engineFailedSent) return else engineFailedSent = true
            LiveEvent.SwitchedToTinyModel -> Unit
        }
        _events.tryEmit(event)
    }

    private fun closeEngine(e: SpeechEngine?) {
        try {
            (e as? Closeable)?.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "LiveSession"
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
