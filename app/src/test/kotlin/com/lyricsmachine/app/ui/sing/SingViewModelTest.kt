package com.lyricsmachine.app.ui.sing

import com.lyricsmachine.app.live.LiveEvent
import com.lyricsmachine.app.speech.ModelSpec
import com.lyricsmachine.app.speech.ModelState
import com.lyricsmachine.app.store.SongEntity
import com.lyricsmachine.app.store.SongMode
import com.lyricsmachine.core.Lang
import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming
import com.lyricsmachine.core.lyrics.Lyrics
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.playback.PlaybackPosition
import com.lyricsmachine.core.playback.TimingLookup
import com.lyricsmachine.core.tracker.TrackerSnapshot
import com.lyricsmachine.core.tracker.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SingViewModelTest {
    // Original text. Lyric (non-spacer) lines: 1, 2, 5, 6. Spacers: 0 "[Intro]", 3 blank, 4 "[Chorus]", 7 "(hum)".
    private val lyricsText = """
        [Intro]
        [00:01.00]Paper lanterns drift along the river
        [00:05.00]Counting every light we left behind

        [Chorus]
        [00:10.00]Hold the evening steady in your hands
        [00:14.00]Let the morning wait a little longer
        (hum)
    """.trimIndent()
    private val parsed: Lyrics = LyricsParser.parse(lyricsText)

    private val lrcLines = listOf(
        LineTiming(1, 1_000, 5_000),
        LineTiming(2, 5_000, 10_000),
        LineTiming(5, 10_000, 14_000),
        LineTiming(6, 14_000, 18_000),
    )

    private var song: SongEntity? = SongEntity(
        id = 3, title = "Lantern River", artist = "Test Choir", language = Lang.EN,
        lyricsText = lyricsText, audioUri = "content://audio/3", createdAt = 0,
    )
    private var mode: SongMode? = SongMode.AUDIO_READY
    private var timings: Pair<List<LineTiming>, List<WordTiming>> = lrcLines to emptyList()
    private val models = mutableMapOf<ModelSpec, ModelState>(
        ModelSpec.BASE to ModelState.Ready(File("base.bin")),
        ModelSpec.TINY to ModelState.Ready(File("tiny.bin")),
    )

    private val playbacks = mutableListOf<FakePlayback>()
    private val playbackUris = mutableListOf<String>()
    private val lives = mutableListOf<FakeLive>()
    private val liveSpecs = mutableListOf<ModelSpec>()

    class FakePlayback : PlaybackLike {
        override val position = MutableStateFlow(PlaybackPosition(null, null, 0f))
        override val isPlaying = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow<Long?>(null)
        override val error = MutableStateFlow<String?>(null)
        var plays = 0
        var pauses = 0
        var closed = 0
        val seeks = mutableListOf<Int>()
        override fun play() { plays++; isPlaying.value = true }
        override fun pause() { pauses++; isPlaying.value = false }
        override fun seekToLine(lineIndex: Int) { seeks += lineIndex }
        override fun close() { closed++; isPlaying.value = false }
    }

    class FakeLive : LiveLike {
        override val snapshot = MutableStateFlow(TrackerSnapshot(TrackerState.Listening, 0.0, 1))
        override val events = MutableSharedFlow<LiveEvent>(replay = 3)
        var started = 0
        var stopped = 0
        val jumps = mutableListOf<Int>()
        override fun start() { check(stopped == 0) { "restart after stop" }; started++ }
        override fun stop() { stopped++ }
        override fun jumpTo(lineIndex: Int) { jumps += lineIndex }
    }

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(manual: Boolean = false) = SingViewModel(
        songId = 3,
        manual = manual,
        loadSong = { song },
        songMode = { mode },
        loadTimings = { timings },
        modelState = { models[it] ?: ModelState.Missing },
        playbackFactory = { uri: String, _: TimingLookup -> playbackUris += uri; FakePlayback().also { playbacks += it } },
        liveFactory = { _: Lyrics, _: Lang?, spec: ModelSpec, _: CoroutineScope ->
            liveSpecs += spec
            FakeLive().also { lives += it }
        },
    )

    private fun noModels() {
        models[ModelSpec.BASE] = ModelState.Missing
        models[ModelSpec.TINY] = ModelState.Missing
    }

    /** A view model in LIVE mode with the mic permission granted and a running session. */
    private fun liveVm(): SingViewModel {
        mode = SongMode.LYRICS_ONLY
        val vm = vm()
        assertTrue(vm.state.value.requestMic)
        vm.onMicPermissionResult(true)
        return vm
    }

    private fun manualVm(): SingViewModel {
        mode = SongMode.AUDIO_READY
        timings = emptyList<LineTiming>() to emptyList()
        return vm()
    }

    // --- brief tests ---

    @Test fun `audio ready starts in playback mode`() {
        val vm = vm()
        val s = vm.state.value
        assertEquals(SingMode.PLAYBACK, s.mode)
        assertEquals(listOf("content://audio/3"), playbackUris)
        assertEquals(8, s.lines.size)
        assertEquals("Lantern River", s.title)
        assertFalse(s.isRunning) // no autoplay
        assertTrue(lives.isEmpty())

        playbacks.single().position.value = PlaybackPosition(5, 2, 0.5f)
        assertEquals(5, vm.state.value.currentLine)
        assertEquals(2, vm.state.value.currentWord)
        assertEquals(0.5f, vm.state.value.wordProgress, 0.001f)

        vm.toggle()
        assertEquals(1, playbacks.single().plays)
        assertTrue(vm.state.value.isRunning)
        vm.toggle()
        assertEquals(1, playbacks.single().pauses)
        assertFalse(vm.state.value.isRunning)
    }

    @Test fun `lyrics only starts in live mode`() {
        mode = SongMode.LYRICS_ONLY
        val vm = vm()
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertTrue(vm.state.value.requestMic)
        assertTrue(lives.isEmpty()) // waits for the permission
        vm.onMicPermissionResult(true)
        assertFalse(vm.state.value.requestMic)
        assertEquals(listOf(ModelSpec.BASE), liveSpecs)
        assertEquals(1, lives.single().started)
        assertTrue(vm.state.value.isRunning)
        assertTrue(playbacks.isEmpty())

        val tokenOfLine5Word1 = parsed.firstTokenOfLine(5)!! + 1
        lives.single().snapshot.value = TrackerSnapshot(TrackerState.Locked, tokenOfLine5Word1 + 0.25, 5)
        assertEquals(5, vm.state.value.currentLine)
        assertEquals(1, vm.state.value.currentWord)
        assertEquals(0.25f, vm.state.value.wordProgress, 0.001f)
        assertEquals(TrackerState.Locked, vm.state.value.trackerState)
    }

    @Test fun `audio not analysed starts in live mode on tiny when base is missing`() {
        mode = SongMode.AUDIO_NOT_ANALYSED
        models[ModelSpec.BASE] = ModelState.Missing
        val vm = vm()
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        vm.onMicPermissionResult(true)
        assertEquals(listOf(ModelSpec.TINY), liveSpecs)
    }

    @Test fun `sing screen with no timings allows manual stepping`() {
        val vm = manualVm()
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
        assertEquals(SingCopy.NO_TIMINGS, vm.state.value.message)
        assertTrue(playbacks.isEmpty())
        assertEquals(1, vm.state.value.currentLine)
        vm.nudge(1)
        assertEquals(2, vm.state.value.currentLine)
        vm.nudge(-1)
        assertEquals(1, vm.state.value.currentLine)
        vm.toggle() // no-op in manual
        assertFalse(vm.state.value.isRunning)
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
    }

    @Test fun `nudge clamps to first and last line`() {
        val vm = manualVm()
        vm.nudge(-1)
        assertEquals(1, vm.state.value.currentLine) // not the "[Intro]" spacer
        repeat(10) { vm.nudge(1) }
        assertEquals(6, vm.state.value.currentLine) // not the trailing "(hum)" spacer
        vm.nudge(-100)
        assertEquals(1, vm.state.value.currentLine)
    }

    @Test fun `tap line seeks playback`() {
        val vm = vm()
        vm.tapLine(5)
        assertEquals(listOf(5), playbacks.single().seeks)
        assertEquals(5, vm.state.value.currentLine)
    }

    @Test fun `tap line jumps live tracker`() {
        val vm = liveVm()
        vm.tapLine(5)
        assertEquals(listOf(5), lives.single().jumps)
        assertEquals(5, vm.state.value.currentLine)
    }

    @Test fun `denied permission falls back to manual`() {
        mode = SongMode.LYRICS_ONLY
        val vm = vm()
        vm.onMicPermissionResult(false)
        val s = vm.state.value
        assertEquals(SingMode.MANUAL, s.mode)
        assertEquals("Microphone access is needed to follow you live. Tap to allow.", s.message)
        assertEquals(MessageAction.ALLOW_MIC, s.messageAction)
        assertFalse(s.requestMic)
        assertTrue(lives.isEmpty())

        // Tapping the message asks again; granting then starts live.
        vm.requestMicAgain()
        assertTrue(vm.state.value.requestMic)
        vm.onMicPermissionResult(true)
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertEquals(1, lives.single().started)
        assertNull(vm.state.value.message)
    }

    // --- controller rulings ---

    @Test fun `manual nav arg starts manual`() {
        mode = SongMode.LYRICS_ONLY
        noModels()
        val vm = vm(manual = true)
        val s = vm.state.value
        assertEquals(SingMode.MANUAL, s.mode)
        assertEquals(SingCopy.LYRICS_ONLY, s.message)
        assertFalse(s.requestMic)
        assertTrue(lives.isEmpty())
        assertTrue(playbacks.isEmpty())
    }

    @Test fun `no model falls back to manual with a message`() {
        mode = SongMode.LYRICS_ONLY
        noModels()
        val vm = vm()
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
        assertEquals(SingCopy.NO_MODEL, vm.state.value.message)
        assertFalse(vm.state.value.requestMic)
        assertTrue(lives.isEmpty())
    }

    @Test fun `use microphone without a model stays in playback with a message`() {
        noModels()
        val vm = vm()
        vm.useMicrophone()
        assertEquals(SingMode.PLAYBACK, vm.state.value.mode)
        assertEquals("The speech model isn't downloaded yet.", vm.state.value.message)
        assertEquals(0, playbacks.single().closed)
        assertFalse(vm.state.value.requestMic)
    }

    @Test fun `use microphone stops playback and starts live`() {
        val vm = vm()
        vm.toggle()
        vm.useMicrophone()
        assertEquals(1, playbacks.single().closed)
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertTrue(vm.state.value.requestMic)
        vm.onMicPermissionResult(true)
        assertEquals(1, lives.single().started)
        vm.tapLine(2)
        assertTrue(playbacks.single().seeks.isEmpty())
        assertEquals(listOf(2), lives.single().jumps)
    }

    @Test fun `engine failed switches to manual with a message`() {
        val vm = liveVm()
        val live = lives.single()
        live.events.tryEmit(LiveEvent.EngineFailed)
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
        assertEquals(SingCopy.ENGINE_FAILED, vm.state.value.snackbar)
        assertEquals(1, live.stopped)
        assertFalse(vm.state.value.isRunning)
        assertNull(vm.state.value.trackerState)
        vm.tapLine(5)
        assertEquals(5, vm.state.value.currentLine)
        assertTrue(live.jumps.isEmpty())
    }

    @Test fun `live events are shown once even if re-collected`() {
        val vm = liveVm()
        val live = lives.single()
        live.events.tryEmit(LiveEvent.SwitchedToTinyModel)
        live.events.tryEmit(LiveEvent.SwitchedToTinyModel) // a replay seen again
        live.events.tryEmit(LiveEvent.StillTooSlow)
        assertEquals("Switched to a faster model for this device.", vm.state.value.snackbar)
        vm.snackbarShown()
        assertEquals("This device is struggling — the lyrics may lag.", vm.state.value.snackbar)
        vm.snackbarShown()
        assertNull(vm.state.value.snackbar)
        live.events.tryEmit(LiveEvent.StillTooSlow)
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `playback error shows our copy not the raw player text`() {
        val vm = vm()
        val raw = "Source error: FileNotFoundException content://audio/3"
        playbacks.single().error.value = raw
        val s = vm.state.value
        assertEquals("This audio file can't be played. You can sing with the microphone instead.", s.message)
        assertEquals(MessageAction.USE_MIC, s.messageAction)
        assertFalse(s.message!!.contains("FileNotFound"))
        assertFalse(s.isRunning)
    }

    @Test fun `nudge skips spacers`() {
        val vm = manualVm()
        vm.nudge(1)
        assertEquals(2, vm.state.value.currentLine)
        vm.nudge(1)
        assertEquals(5, vm.state.value.currentLine) // skips blank 3 and "[Chorus]" 4
        vm.nudge(-1)
        assertEquals(2, vm.state.value.currentLine)
    }

    @Test fun `nudge in playback seeks to the next lyric line`() {
        val vm = vm()
        playbacks.single().position.value = PlaybackPosition(2, null, 0f)
        vm.nudge(1)
        assertEquals(listOf(5), playbacks.single().seeks)
    }

    @Test fun `tap on a spacer goes to the next lyric line`() {
        val vm = manualVm()
        vm.tapLine(4)
        assertEquals(5, vm.state.value.currentLine)
        vm.tapLine(0)
        assertEquals(1, vm.state.value.currentLine)
        vm.tapLine(7) // trailing spacer: nothing after it, so the last lyric line
        assertEquals(6, vm.state.value.currentLine)
    }

    @Test fun `tap on a spacer seeks playback to the next lyric line`() {
        val vm = vm()
        vm.tapLine(3)
        assertEquals(listOf(5), playbacks.single().seeks)
    }

    @Test fun `backgrounding pauses playback`() {
        val vm = vm()
        vm.toggle()
        assertTrue(vm.state.value.isRunning)
        vm.onBackgrounded()
        assertEquals(1, playbacks.single().pauses)
        assertFalse(vm.state.value.isRunning)
        assertEquals(1, playbacks.single().plays) // no auto-resume
    }

    @Test fun `backgrounding stops live listening`() {
        val vm = liveVm()
        vm.onBackgrounded()
        assertEquals(1, lives.single().stopped)
        assertFalse(vm.state.value.isRunning)
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertEquals(1, lives.size) // no auto-resume
    }

    @Test fun `toggle in live stops and restarts with a new session`() {
        val vm = liveVm()
        vm.tapLine(5)
        vm.toggle()
        assertEquals(1, lives[0].stopped)
        assertFalse(vm.state.value.isRunning)
        vm.toggle()
        assertTrue(vm.state.value.requestMic)
        vm.onMicPermissionResult(true)
        assertEquals(2, lives.size)
        assertEquals(1, lives[1].started)
        assertEquals(listOf(5), lives[1].jumps) // resumes where the singer was
        assertTrue(vm.state.value.isRunning)
    }

    @Test fun `restart after a switch to tiny keeps the tiny model`() {
        val vm = liveVm()
        lives[0].events.tryEmit(LiveEvent.SwitchedToTinyModel)
        vm.toggle()
        vm.toggle()
        vm.onMicPermissionResult(true)
        assertEquals(listOf(ModelSpec.BASE, ModelSpec.TINY), liveSpecs)
    }

    @Test fun `mic that cannot start falls back to manual`() {
        mode = SongMode.LYRICS_ONLY
        val vm = SingViewModel(
            songId = 3, manual = false, loadSong = { song }, songMode = { mode }, loadTimings = { timings },
            modelState = { models[it] ?: ModelState.Missing },
            playbackFactory = { _, _ -> error("unused") },
            liveFactory = { _, _, _, _ ->
                object : LiveLike by FakeLive() {
                    override fun start() = throw IllegalStateException("AudioRecord could not be initialised")
                }
            },
        )
        vm.onMicPermissionResult(true)
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
        assertEquals(SingCopy.MIC_FAILED, vm.state.value.message)
    }

    @Test fun `tracker chip labels`() {
        assertEquals("Listening", trackerLabel(TrackerState.Listening))
        assertEquals("Following", trackerLabel(TrackerState.Locked))
        assertEquals("Following", trackerLabel(TrackerState.Coasting))
        assertEquals("Paused", trackerLabel(TrackerState.Paused))
        assertEquals("Lost — tap a line to help", trackerLabel(TrackerState.Lost))
    }

    @Test fun `missing song shows a message`() {
        song = null
        val vm = vm()
        assertEquals(SingCopy.NOT_FOUND, vm.state.value.message)
        assertTrue(vm.state.value.lines.isEmpty())
        vm.nudge(1)
        vm.tapLine(0)
        assertNull(vm.state.value.currentLine)
    }
}
