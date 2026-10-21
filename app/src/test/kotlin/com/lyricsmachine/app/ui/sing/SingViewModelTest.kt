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
        assertEquals(1, vm.state.value.currentVerse)

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
        assertEquals(1, vm.state.value.currentVerse)
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
        assertEquals(5, vm.state.value.currentLine) // a whole verse
        vm.nudge(-1)
        assertEquals(1, vm.state.value.currentLine)
        vm.toggle() // no-op in manual
        assertFalse(vm.state.value.isRunning)
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
    }

    @Test fun `nudge clamps to first and last verse`() {
        val vm = manualVm()
        vm.nudge(-1)
        assertEquals(1, vm.state.value.currentLine) // first line of verse 0, not the "[Intro]" spacer
        assertEquals(0, vm.state.value.currentVerse)
        repeat(10) { vm.nudge(1) }
        assertEquals(5, vm.state.value.currentLine) // first line of the last verse
        assertEquals(1, vm.state.value.currentVerse)
        vm.nudge(-100)
        assertEquals(1, vm.state.value.currentLine)
        assertEquals(0, vm.state.value.currentVerse)
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
        assertEquals(SingCopy.ENGINE_FAILED, vm.state.value.snackbar?.text)
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
        assertEquals("Switched to a faster model for this device.", vm.state.value.snackbar?.text)
        vm.snackbarShown(vm.state.value.snackbar!!.id)
        assertEquals("This device is struggling — the lyrics may lag.", vm.state.value.snackbar?.text)
        vm.snackbarShown(vm.state.value.snackbar!!.id)
        assertNull(vm.state.value.snackbar)
        live.events.tryEmit(LiveEvent.StillTooSlow)
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `the same snackbar text queued twice does not jam the queue`() {
        val vm = liveVm()
        // Two sessions each report StillTooSlow (same text twice in a row), then the engine fails.
        lives[0].events.tryEmit(LiveEvent.StillTooSlow)
        vm.toggle(); vm.toggle(); vm.onMicPermissionResult(true)
        lives[1].events.tryEmit(LiveEvent.StillTooSlow)
        lives[1].events.tryEmit(LiveEvent.EngineFailed)
        val shown = mutableListOf<String>()
        val ids = mutableSetOf<Long>()
        repeat(5) {
            val n = vm.state.value.snackbar ?: return@repeat
            assertTrue("each notice has its own id", ids.add(n.id))
            shown += n.text
            vm.snackbarShown(n.id)
        }
        assertEquals(listOf(SingCopy.STILL_TOO_SLOW, SingCopy.STILL_TOO_SLOW, SingCopy.ENGINE_FAILED), shown)
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `acknowledging a stale snackbar id does not drop the current one`() {
        val vm = liveVm()
        lives[0].events.tryEmit(LiveEvent.StillTooSlow)
        val first = vm.state.value.snackbar!!
        vm.snackbarShown(first.id)
        vm.snackbarShown(first.id) // a repeated acknowledgement after a recomposition
        lives[0].events.tryEmit(LiveEvent.SwitchedToTinyModel)
        assertEquals(SingCopy.SWITCHED_TO_TINY, vm.state.value.snackbar?.text)
        vm.snackbarShown(first.id)
        assertEquals(SingCopy.SWITCHED_TO_TINY, vm.state.value.snackbar?.text)
    }

    @Test fun `return from settings starts listening only when we sent the user there`() {
        mode = SongMode.LYRICS_ONLY
        val vm = vm()
        vm.onMicPermissionResult(false)
        // Returning to the screen without having opened settings never starts anything.
        vm.onReturnedFromSettings(granted = true)
        assertTrue(lives.isEmpty())
        assertEquals(SingMode.MANUAL, vm.state.value.mode)

        vm.openedSettingsForMic()
        assertTrue(vm.state.value.sentToSettings)
        vm.onReturnedFromSettings(granted = false)
        assertTrue(lives.isEmpty())
        assertFalse(vm.state.value.sentToSettings)

        vm.openedSettingsForMic()
        vm.onReturnedFromSettings(granted = true)
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertEquals(1, lives.single().started)
        assertFalse(vm.state.value.sentToSettings)

        // Later returns (e.g. after backgrounding) do not auto-resume.
        vm.onBackgrounded()
        vm.onReturnedFromSettings(granted = true)
        assertEquals(1, lives.size)
    }

    @Test fun `playback error hides the toggle and makes it a no-op`() {
        val vm = vm()
        assertTrue(vm.state.value.canToggle)
        playbacks.single().error.value = "Source error"
        assertFalse(vm.state.value.canToggle)
        vm.toggle()
        assertEquals(0, playbacks.single().plays)
        assertFalse(vm.state.value.isRunning)
        vm.nudge(1) // acts like manual: moves the verse, no seek on the broken player
        assertEquals(5, vm.state.value.currentLine)
        assertTrue(playbacks.single().seeks.isEmpty())
        vm.useMicrophone()
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertTrue(vm.state.value.canToggle)
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

    @Test fun `nudge moves by verse to its first lyric line`() {
        val vm = manualVm()
        vm.tapLine(2) // second line of verse 0
        vm.nudge(1)
        assertEquals(5, vm.state.value.currentLine) // skips blank 3 and "[Chorus]" 4
        vm.nudge(-1)
        assertEquals(1, vm.state.value.currentLine) // first line of the previous verse
    }

    @Test fun `verses are exposed in state`() {
        val vm = manualVm()
        assertEquals(listOf(listOf(1, 2), listOf(5, 6)), vm.state.value.verses.map { it.lineIndices })
        assertEquals(listOf<String?>("[Intro]", "[Chorus]"), vm.state.value.verses.map { it.label })
    }

    @Test fun `currentVerse follows currentLine across a verse boundary`() {
        val vm = vm()
        val pos = playbacks.single().position
        pos.value = PlaybackPosition(2, null, 0f)
        assertEquals(0, vm.state.value.currentVerse)
        pos.value = PlaybackPosition(5, null, 0f)
        assertEquals(1, vm.state.value.currentVerse)
        pos.value = PlaybackPosition(6, null, 0f)
        assertEquals(1, vm.state.value.currentVerse)
    }

    @Test fun `null currentLine shows the first verse`() {
        val vm = vm()
        playbacks.single().position.value = PlaybackPosition(null, null, 0f)
        assertNull(vm.state.value.currentLine)
        assertEquals(0, vm.state.value.currentVerse)
    }

    @Test fun `tapping the next verse preview goes to its first line`() {
        val vm = vm()
        assertEquals(5, vm.nextVersePreviewLine())
        assertEquals(5, vm.state.value.previewLine)
        vm.tapPreview()
        assertEquals(listOf(5), playbacks.single().seeks)
        assertEquals(1, vm.state.value.currentVerse)
    }

    @Test fun `preview line is null on the last verse and tapPreview does nothing`() {
        val vm = vm()
        vm.nudge(1)
        assertNull(vm.nextVersePreviewLine())
        assertNull(vm.state.value.previewLine)
        vm.tapPreview()
        assertEquals(listOf(5), playbacks.single().seeks)
        assertEquals(1, vm.state.value.currentVerse)
    }

    // Three verses: v0 = lines 0,1; v1 = lines 3,4; v2 = line 6.
    private fun threeVerseVm(timedLines: List<Int>): SingViewModel {
        song = song!!.copy(lyricsText = "Moss on the lamp\nTwo owls at dusk\n\nCopper kettle sings\nSteam over the sill\n\nLast ember glows")
        timings = timedLines.map { LineTiming(it, it * 1_000L, it * 1_000L + 900) } to emptyList()
        return vm()
    }

    @Test fun `playback nudge onto an untimed first line seeks to the verse's first timed line`() {
        timings = listOf(LineTiming(1, 1_000, 5_000), LineTiming(2, 5_000, 10_000), LineTiming(6, 14_000, 18_000)) to emptyList()
        val vm = vm()
        vm.nudge(1) // verse 1 = lines 5,6; line 5 has no timing
        assertEquals(listOf(6), playbacks.single().seeks)
        assertEquals(6, vm.state.value.currentLine)
        assertEquals(1, vm.state.value.currentVerse)
    }

    @Test fun `playback nudge skips verses without timed lines`() {
        val vm = threeVerseVm(listOf(0, 1, 6))
        vm.nudge(1)
        assertEquals(listOf(6), playbacks.single().seeks)
        assertEquals(2, vm.state.value.currentVerse)
        vm.nudge(-1)
        assertEquals(listOf(6, 0), playbacks.single().seeks)
        assertEquals(0, vm.state.value.currentVerse)
    }

    @Test fun `playback nudge with nothing timed ahead does nothing`() {
        val vm = threeVerseVm(listOf(0, 1))
        vm.nudge(1)
        assertTrue(playbacks.single().seeks.isEmpty())
        assertEquals(0, vm.state.value.currentVerse)
        assertEquals(0, vm.state.value.currentLine)
    }

    @Test fun `playback tap on an untimed line seeks to the next timed line`() {
        timings = listOf(LineTiming(2, 5_000, 10_000), LineTiming(5, 10_000, 14_000), LineTiming(6, 14_000, 18_000)) to emptyList()
        val vm = vm()
        vm.tapLine(1)
        assertEquals(listOf(2), playbacks.single().seeks)
        assertEquals(2, vm.state.value.currentLine)
    }

    @Test fun `playback tap falls through to later verses then to the nearest earlier timed line`() {
        val vm = threeVerseVm(listOf(0, 1, 6))
        vm.tapLine(3) // verse 1 has nothing timed -> next verse
        assertEquals(listOf(6), playbacks.single().seeks)
        assertEquals(2, vm.state.value.currentVerse)
        val vm2 = threeVerseVm(listOf(0, 1))
        vm2.tapLine(6) // nothing at or after -> nearest before
        assertEquals(listOf(1), playbacks.last().seeks)
        assertEquals(1, vm2.state.value.currentLine)
    }

    @Test fun `nudge in live jumps to the verse start`() {
        val vm = liveVm()
        vm.nudge(1)
        assertEquals(listOf(5), lives.single().jumps)
        assertEquals(1, vm.state.value.currentVerse)
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

    @Test fun `audio that can no longer be opened tells the user and follows the microphone`() {
        mode = SongMode.LYRICS_ONLY // the repository reports LYRICS_ONLY when the stored audio is unreadable
        val vm = vm()
        val expected = "This song's audio file can't be opened any more — following you with the microphone instead."
        assertEquals(expected, vm.state.value.message)
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertTrue(vm.state.value.requestMic)
        vm.onMicPermissionResult(true)
        assertEquals(1, lives.single().started)
        assertEquals(expected, vm.state.value.message)
        assertTrue(playbacks.isEmpty())

        // Once live tracking fails, "following you with the microphone" is no longer true.
        lives.single().events.tryEmit(LiveEvent.EngineFailed)
        assertEquals(SingMode.MANUAL, vm.state.value.mode)
        assertNull(vm.state.value.message)
    }

    @Test fun `lyrics only song without audio shows no audio notice`() {
        song = song!!.copy(audioUri = null)
        mode = SongMode.LYRICS_ONLY
        val vm = vm()
        assertEquals(SingMode.LIVE, vm.state.value.mode)
        assertNull(vm.state.value.message)
    }
}
