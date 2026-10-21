# Lyrics Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an offline Android karaoke app that keeps user-supplied lyrics in sync with a song — via one-time analysis of an audio file (mode 1) or live microphone tracking (mode 2).

**Architecture:** A pure-Kotlin `core` module holds all the logic that decides *where we are in the lyrics* (parser, fuzzy aligner, live tracker, timing lookup, audio utilities, `SpeechEngine` interface) and is unit-tested on the JVM. An Android `app` module wraps it with Compose UI, Room storage, a whisper.cpp JNI speech engine, audio decoding, mic capture and ExoPlayer playback.

**Tech Stack:** Kotlin 2.1, JDK 17 target, Gradle 8.14 + version catalog, JUnit 5 (core), Jetpack Compose + Material 3, Room, WorkManager, Media3 ExoPlayer, whisper.cpp v1.7.6 via NDK/CMake, Robolectric (app JVM tests).

**Spec:** `docs/superpowers/specs/2026-10-21-lyrics-machine-design.md`

## Global Constraints

- Languages: English (`en`), Bahasa Malaysia (`ms`), Bahasa Indonesia (`id`), and Mixed (Whisper auto-detect).
- Fully offline after the one-time model download; no lyrics fetching; no cloud calls.
- `core` must not depend on any Android artifact.
- minSdk 26, targetSdk 35, ABIs `arm64-v8a` and `armeabi-v7a`.
- Default model `ggml-base-q5_1.bin`; fallback `ggml-tiny-q5_1.bin`. Downloaded on first launch, SHA-256 verified.
- Audio files are referenced by persisted content URI, never copied.
- No copyrighted lyrics or audio in the repo. All fixtures are original text written for this project.
- Tracker timing constants live in one `TrackerConfig` object: chunk interval 2000 ms, analysed window 4000 ms, search window 1 line back / 4 lines ahead, silence pause 4000 ms, lost after 10000 ms.
- Low-quality analysis threshold: `matchRatio < 0.40`.
- Fuzzy token match threshold: similarity `>= 0.75`.

## Environment Note

Part A (Tasks 1–7) builds and tests with only a JDK + Gradle (`gradle :core:test`). Part B (Tasks 8–16) requires the Android SDK/NDK and network access to Google Maven, GitHub (whisper.cpp) and Hugging Face (models) — build it in Android Studio (Ladybug or newer) on a dev machine. In the cloud session used to write this plan those hosts were blocked, so Part B was not verifiable there.

## Review Focus

1. **Lyrics with no matches at all** (wrong lyrics for the recording, or an instrumental track) — expect `matchRatio = 0`, no timings saved, the low-quality warning shown, and the Sing screen still usable with manual line stepping. Test: Task 3 `alignFull returns empty timings and zero ratio when nothing matches`; Task 15 `sing screen with no timings allows manual stepping`.
2. **A chorus repeated three times with identical words** — expect live tracking to move to the *next* repeat, never back to an earlier one, unless the user taps. Test: Task 4 `locate prefers candidate at or after preferAfter`; Task 5 `tracker does not jump back to earlier chorus`.
3. **Long instrumental break with music still audible in the room** — expect the tracker to coast no more than `maxCoastLines` beyond the last confident match, then go Lost and re-acquire on the next sung line. Test: Task 5 `coasting is capped during instrumental`.
4. **Lyrics pasted with Windows line endings, trailing spaces, emoji or `.lrc` metadata tags** — expect clean lines with no empty words and metadata dropped. Test: Task 2 `parse handles CRLF, whitespace, emoji and lrc metadata`.
5. **Audio file deleted or permission revoked after the song was saved** — expect the song to drop to "Lyrics only" with a message, not crash. Test: Task 9 `song with unreadable audio uri is reported as lyrics only`.

---

## Part A — `core` (pure Kotlin)

### Task 1: Gradle project skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`
- Create: `core/build.gradle.kts`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/Lang.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/LangTest.kt`

**Interfaces:**
- Produces: `enum class Lang(val whisperCode: String?) { EN("en"), MS("ms"), ID("id"), MIXED(null) }`

- [ ] **Step 1:** Write `settings.gradle.kts` including only `:core` for now (`:app` is added in Task 8). Root `build.gradle.kts` declares the Kotlin JVM plugin `apply false`. `core/build.gradle.kts` applies `kotlin("jvm")`, `jvmToolchain(17)`, JUnit 5 (`junit-jupiter` 5.11.x) with `useJUnitPlatform()`. Generate the wrapper with `gradle wrapper --gradle-version 8.14.3`. `.gitignore` covers `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`.
- [ ] **Step 2: Write the failing test**

```kotlin
class LangTest {
    @Test fun `mixed has no whisper code`() = assertNull(Lang.MIXED.whisperCode)
    @Test fun `malay maps to ms`() = assertEquals("ms", Lang.MS.whisperCode)
}
```

- [ ] **Step 3:** Run `./gradlew :core:test` — expect FAIL (unresolved `Lang`).
- [ ] **Step 4:** Implement `Lang` as in Interfaces.
- [ ] **Step 5:** Run `./gradlew :core:test` — expect BUILD SUCCESSFUL, 2 tests passed.
- [ ] **Step 6:** Commit: `git add -A && git commit -m "chore: gradle skeleton with core module"`

### Task 2: Lyrics model and LyricsParser

**Files:**
- Create: `core/src/main/kotlin/com/lyricsmachine/core/lyrics/Lyrics.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/lyrics/LyricsParser.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/lyrics/LyricsParserTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class LyricWord(val display: String, val token: String)
  data class LyricLine(val index: Int, val words: List<LyricWord>, val isSpacer: Boolean, val lrcStartMs: Long?)
  data class TokenRef(val lineIndex: Int, val wordIndex: Int, val token: String)
  data class Lyrics(val lines: List<LyricLine>) {
      val tokens: List<TokenRef>          // all non-spacer words in order, empty tokens skipped
      fun lineOfToken(tokenIndex: Int): Int
      fun firstTokenOfLine(lineIndex: Int): Int?   // null for spacer lines
  }
  object LyricsParser {
      fun parse(raw: String): Lyrics
      fun normalize(word: String): String
  }
  ```

Rules: split on whitespace; `normalize` = NFKC, lowercase (Locale.ROOT), remove hyphens (so `kupu-kupu` → `kupukupu`), then drop every char that is not a Unicode letter or digit. A line that is empty, or matches `^\s*[\[(][^\])]*[\])]\s*$` and is not an LRC timestamp, is a spacer. LRC timestamp: `^\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]` — strip it and set `lrcStartMs` (centiseconds if 2 digits, ms if 3). LRC metadata tags (`[ar:…]`, `[ti:…]`, `[al:…]`, `[by:…]`, `[offset:…]`, `[length:…]`) are dropped entirely, not kept as spacers. Line indices are renumbered after dropping.

- [ ] **Step 1: Write failing tests** (fixtures are original text)

```kotlin
@Test fun `normalize strips punctuation and case`() = assertEquals("hello", LyricsParser.normalize("Hello,"))
@Test fun `normalize joins malay reduplication`() = assertEquals("kupukupu", LyricsParser.normalize("Kupu-kupu"))
@Test fun `plain text gives one line per row`() {
    val l = LyricsParser.parse("the lantern glows\non the quiet river")
    assertEquals(2, l.lines.size); assertEquals(7, l.tokens.size)
    assertEquals("lantern", l.tokens[1].token)
}
@Test fun `section labels and blanks are spacers excluded from tokens`() {
    val l = LyricsParser.parse("[Chorus]\nbulan terang\n\n(Bridge)\ndi atas bukit")
    assertEquals(listOf(true, false, true, true, false), l.lines.map { it.isSpacer })
    assertEquals(5, l.tokens.size); assertNull(l.firstTokenOfLine(0)); assertEquals(2, l.firstTokenOfLine(4))
}
@Test fun `lrc timestamps parsed`() {
    val l = LyricsParser.parse("[00:12.50]the lantern glows\n[01:02.345]on the river")
    assertEquals(12_500L, l.lines[0].lrcStartMs); assertEquals(62_345L, l.lines[1].lrcStartMs)
    assertEquals("the", l.lines[0].words[0].display)
}
@Test fun `parse handles CRLF, whitespace, emoji and lrc metadata`() {
    val l = LyricsParser.parse("[ar:Someone]\r\n[ti:Song]\r\n  hati  saya 🎵 \r\n")
    assertEquals(1, l.lines.size); assertEquals(listOf("hati", "saya"), l.tokens.map { it.token })
}
@Test fun `lineOfToken maps back`() {
    val l = LyricsParser.parse("a b\nc"); assertEquals(1, l.lineOfToken(2))
}
```

- [ ] **Step 2:** Run `./gradlew :core:test --tests '*LyricsParserTest*'` — expect FAIL.
- [ ] **Step 3:** Implement per Interfaces and Rules. Words whose token is empty (e.g. "🎵") stay out of `words`.
- [ ] **Step 4:** Run the same command — expect all PASS.
- [ ] **Step 5:** Commit `feat(core): lyrics parser`.

### Task 3: Token similarity and Aligner.alignFull

**Files:**
- Create: `core/src/main/kotlin/com/lyricsmachine/core/speech/HeardWord.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/align/Similarity.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/align/Alignment.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/align/Aligner.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/align/SimilarityTest.kt`, `AlignFullTest.kt`

**Interfaces:**
- Consumes: `Lyrics`, `TokenRef`, `LyricsParser.normalize` (Task 2)
- Produces:
  ```kotlin
  data class HeardWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
  object Similarity {
      const val MATCH_THRESHOLD = 0.75
      fun score(a: String, b: String): Double   // 1 - levenshtein/maxLen, 1.0 for equal, 0.0 if either empty
  }
  data class WordTiming(val lineIndex: Int, val wordIndex: Int, val startMs: Long, val endMs: Long, val matched: Boolean)
  data class LineTiming(val lineIndex: Int, val startMs: Long, val endMs: Long)
  data class Alignment(val lines: List<LineTiming>, val words: List<WordTiming>, val matchRatio: Double) {
      val isLowQuality: Boolean get() = matchRatio < 0.40
  }
  object Aligner {
      fun alignFull(lyrics: Lyrics, heard: List<HeardWord>): Alignment
  }
  ```

Algorithm for `alignFull` (global DP over lyric tokens L and normalised heard tokens H; drop heard tokens that normalise to empty):
- Moves and scores: diagonal 1:1 → `+2*s` if `s >= 0.75` else `-1`; lyric gap `-1`; heard gap `-0.5` (Whisper often hallucinates filler); **join** heard[i]+heard[i+1] vs L[j] and **split** H[i] vs L[j]+L[j+1] → `+2*s` only if `s >= 0.75`, else disallowed.
- Matched lyric tokens take the start/end of their heard word(s).
- Unmatched runs between two matched tokens: split the gap `[prevEnd, nextStart]` evenly. Leading run before the first match: each word gets 400 ms ending at the first match's start, clamped at 0. Trailing run: 400 ms each after the last match.
- Enforce monotonic non-decreasing start times.
- Line timing = first word start to last word end. Spacer lines get no timing.
- `matchRatio` = matched lyric tokens / total lyric tokens. If zero tokens match, return `Alignment(emptyList(), emptyList(), 0.0)`.

- [ ] **Step 1: Write failing tests.** Use a helper `heard(vararg pairs: Pair<String, Long>)` that builds 300 ms words.

```kotlin
// SimilarityTest
@Test fun `equal is 1`() = assertEquals(1.0, Similarity.score("sayang", "sayang"))
@Test fun `one typo passes threshold`() = assertTrue(Similarity.score("lantern", "lanturn") >= 0.75)
@Test fun `different words fail`() = assertTrue(Similarity.score("river", "bukit") < 0.75)

// AlignFullTest — lyrics: "the lantern glows\non the quiet river"
@Test fun `perfect transcript gives exact timings and ratio 1`()
    // heard words at 1000,1500,2000,4000,4500,5000,5500 → lines[0].startMs == 1000, lines[1].startMs == 4000, matchRatio == 1.0
@Test fun `missing word is interpolated between neighbours`()
    // drop "quiet" → its WordTiming.matched == false, startMs strictly between "the"(4500) end and "river"(5500) start
@Test fun `misheard word still matches`()  // "lanturn" → matched == true
@Test fun `joined heard words match split lyric`()  // lyric "sayangku", heard "sayang","ku" → matched
@Test fun `extra hallucinated words are ignored`()  // inserting "um","yeah" leaves matchRatio == 1.0
@Test fun `repeated chorus aligns to correct occurrence`()
    // lyrics: "rise up high\nwalk the road\nrise up high"; heard covers all three lines at 1000/5000/9000 → lines[2].startMs == 9000
@Test fun `alignFull returns empty timings and zero ratio when nothing matches`()
@Test fun `low quality flagged below 0_40`()  // only 2 of 7 words heard → isLowQuality
@Test fun `start times are monotonic`()
```

- [ ] **Step 2:** Run `./gradlew :core:test --tests '*align*'` — expect FAIL.
- [ ] **Step 3:** Implement `Similarity.score` (classic Levenshtein, two-row) and `Aligner.alignFull` per the algorithm.
- [ ] **Step 4:** Run — expect all PASS.
- [ ] **Step 5:** Commit `feat(core): fuzzy full-song aligner`.

### Task 4: Aligner.locate (live snippet search)

**Files:**
- Modify: `core/src/main/kotlin/com/lyricsmachine/core/align/Aligner.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/align/LocateResult.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/align/LocateTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class LocateResult(val endTokenIndex: Int, val matchedCount: Int, val confidence: Double)
  // endTokenIndex = index of the last matched lyric token + 1 (the "next" position)
  fun Aligner.locate(lyrics: Lyrics, heardTokens: List<String>, fromToken: Int, toToken: Int, preferAfter: Int): LocateResult?
  ```

Local alignment (Smith–Waterman) of `heardTokens` (already normalised) against `lyrics.tokens[fromToken until toToken]`, same scoring as Task 3 (no join/split needed). `confidence = bestScore / (2.0 * heardTokens.size)`, clamped to [0,1]. Return `null` if `matchedCount < 2`. When several end positions tie on score (within 1e-9), choose the smallest `endTokenIndex >= preferAfter`; if none, the largest below it.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `finds snippet inside window`()           // lyrics of 3 lines, heard "on the quiet" → endTokenIndex == 6
@Test fun `returns null for unrelated words`()
@Test fun `respects window bounds`()                 // match outside [from,to) not returned
@Test fun `locate prefers candidate at or after preferAfter`()
    // lyrics "rise up high\nwalk the road\nrise up high\nwalk the road\nrise up high"; heard "rise up high";
    // candidate endTokenIndex values are 3, 9, 15; preferAfter = 7 → 9; preferAfter = 10 → 15; preferAfter = 20 → 15
@Test fun `tolerates one misheard word`()            // confidence > 0.5
```

- [ ] **Step 2:** Run `./gradlew :core:test --tests '*LocateTest*'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(core): windowed snippet locate`.

### Task 5: LiveTracker

**Files:**
- Create: `core/src/main/kotlin/com/lyricsmachine/core/tracker/TrackerConfig.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/tracker/LiveTracker.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/tracker/LiveTrackerTest.kt`

**Interfaces:**
- Consumes: `Lyrics` (Task 2), `HeardWord` (Task 3), `Aligner.locate` (Task 4)
- Produces:
  ```kotlin
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
  class LiveTracker(lyrics: Lyrics, config: TrackerConfig = TrackerConfig()) {
      fun onChunk(nowMs: Long, heard: List<HeardWord>, audioPresent: Boolean): TrackerSnapshot
      fun onUserJump(nowMs: Long, lineIndex: Int): TrackerSnapshot
      fun snapshot(nowMs: Long): TrackerSnapshot   // called by UI every frame; extrapolates when Locked/Coasting
  }
  ```

Behaviour (spec §4.4):
- Starts `Listening` at token 0, no advance until first lock.
- Search window: from first token of `currentLine - windowLinesBack` to the token after `currentLine + windowLinesAhead`. In `Lost`, the whole song.
- `preferAfter` = floor of current position.
- Lock when `matchedCount >= minLockTokens` or (`matchedCount >= 2` and `confidence >= lockConfidence`): set position to `endTokenIndex`, update pace from the previous lock (`obs = Δtokens/Δsec`, accepted if in `[minPace, maxPace]`, `pace = pace*(1-a) + obs*a`), state `Locked`.
- No lock and `audioPresent`: `Coasting` — position advances at pace, capped at the first token of `lastLockLine + maxCoastLines + 1`.
- `!audioPresent` for ≥ `silencePauseMs`: `Paused` (no advance).
- No lock for ≥ `lostAfterMs` since last lock (or since start, once at least one lock happened): `Lost`.
- `onUserJump`: position = first token of that line, last-lock time = now, state `Locked`.
- `lineIndex` = `lyrics.lineOfToken(min(floor(position), tokens.lastIndex))`.

- [ ] **Step 1: Write failing tests** using a fixed 6-line original lyric, a fake clock (plain `Long`s), and a helper `say(vararg words)` → `List<HeardWord>`.

```kotlin
@Test fun `locks on first confident phrase`()
@Test fun `stays listening on unrelated words`()
@Test fun `snapshot extrapolates at pace while locked`()
@Test fun `pace updates from consecutive locks`()
@Test fun `pauses after silence and does not advance`()
@Test fun `coasting is capped during instrumental`()        // audioPresent=true, no words for 9 s → line <= lastLockLine + 2
@Test fun `goes lost after 10s without lock and reacquires anywhere`()
@Test fun `tracker does not jump back to earlier chorus`()   // chorus x3; after locking in 2nd chorus, hearing chorus words → 2nd or 3rd, never 1st
@Test fun `user jump overrides position`()
```

- [ ] **Step 2:** Run `./gradlew :core:test --tests '*LiveTrackerTest*'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(core): live lyric tracker`.

### Task 6: TimingLookup (mode 1 playback mapping)

**Files:**
- Create: `core/src/main/kotlin/com/lyricsmachine/core/playback/TimingLookup.kt`
- Test: `core/src/test/kotlin/com/lyricsmachine/core/playback/TimingLookupTest.kt`

**Interfaces:**
- Consumes: `LineTiming`, `WordTiming` (Task 3)
- Produces:
  ```kotlin
  data class PlaybackPosition(val lineIndex: Int?, val wordIndex: Int?, val wordProgress: Float)
  class TimingLookup(lines: List<LineTiming>, words: List<WordTiming>) {
      fun at(positionMs: Long): PlaybackPosition   // binary search; lineIndex = last line with startMs <= pos, null before first
      fun startOfLine(lineIndex: Int): Long?
  }
  ```

- [ ] **Step 1: Failing tests:** `before first line is null`, `inside a line returns it`, `between lines keeps previous line`, `word progress is 0_5 at word midpoint`, `startOfLine returns seek target`, `empty timings always null`.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*TimingLookupTest*'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(core): playback timing lookup`.

### Task 7: Audio utilities, SpeechEngine interface, chunked transcription, performance guard

**Files:**
- Create: `core/src/main/kotlin/com/lyricsmachine/core/speech/SpeechEngine.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/speech/ChunkedTranscriber.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/speech/PerformanceGuard.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/audio/AudioMath.kt`
- Create: `core/src/main/kotlin/com/lyricsmachine/core/audio/RollingBuffer.kt`
- Test: matching `*Test.kt` files under `core/src/test/kotlin/com/lyricsmachine/core/{speech,audio}/`

**Interfaces:**
- Produces:
  ```kotlin
  interface SpeechEngine { suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord> }

  object AudioMath {
      const val TARGET_RATE = 16_000
      fun toMonoFloat(interleaved: ShortArray, channels: Int): FloatArray   // average channels, /32768f
      fun resample(mono: FloatArray, fromRate: Int, toRate: Int = TARGET_RATE): FloatArray  // linear interpolation
      fun rms(pcm: FloatArray): Float
      const val SILENCE_RMS = 0.01f
  }
  class RollingBuffer(capacitySamples: Int) { fun append(samples: FloatArray); fun latest(): FloatArray }
  class ChunkedTranscriber(engine: SpeechEngine, chunkMs: Long = 30_000) {
      // feeds consecutive 16 kHz chunks, offsets every HeardWord by the chunk's start time, reports progress 0..1
      suspend fun transcribe(chunks: Sequence<FloatArray>, totalSamples: Long, language: Lang?, onProgress: (Float) -> Unit): List<HeardWord>
  }
  class PerformanceGuard(budgetMs: Long, window: Int = 3) {
      fun record(inferenceMs: Long)
      val overBudget: Boolean   // median of the last `window` samples > budgetMs
  }
  ```
- `ChunkedTranscriber` must be cancellable (check `coroutineContext.ensureActive()` between chunks).

- [ ] **Step 1: Failing tests:** `stereo averages to mono`, `resample 48k to 16k keeps duration`, `rms of silence is below threshold`, `rolling buffer keeps only latest samples`, `chunked transcriber offsets second chunk by 30s` (fake engine returning one word at 0 ms per chunk → second word startMs == 30_000), `progress reaches 1`, `cancellation stops between chunks`, `performance guard trips on median over budget` (records 2500, 2600, 1000 with budget 2000 → true).
- [ ] **Step 2:** Run `./gradlew :core:test` — new tests FAIL. (Add `kotlinx-coroutines-core` and `kotlinx-coroutines-test` to core.)
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew :core:test` — all PASS.
- [ ] **Step 5:** Commit `feat(core): audio utils, speech interface, chunked transcription`.

---

## Part B — `app` (Android; build in Android Studio)

### Task 8: Android app module skeleton

**Files:**
- Modify: `settings.gradle.kts` (add `:app`, `google()` repo), `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/MainActivity.kt`, `LyricsMachineApp.kt`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/theme/Theme.kt`

**Interfaces:**
- Consumes: `project(":core")`
- Produces: `MainActivity` hosting a Compose `NavHost` with routes `library`, `edit/{songId?}`, `analyse/{songId}`, `sing/{songId}` (placeholder screens until Tasks 13–15). `LyricsMachineApp : Application` exposes a simple manual DI container `AppContainer` (`val container: AppContainer`).

- [ ] **Step 1:** AGP 8.7+, compileSdk/targetSdk 35, minSdk 26, Compose BOM, Material 3, Navigation Compose, Kotlin Compose compiler plugin. Manifest permissions: `RECORD_AUDIO`, `INTERNET` (model download only), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- [ ] **Step 2:** Run `./gradlew :app:assembleDebug` — expect BUILD SUCCESSFUL; install on emulator, app opens to an empty "Library" title.
- [ ] **Step 3:** Commit `chore(app): android module skeleton`.

### Task 9: SongStore (Room)

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/store/{Entities.kt, SongDao.kt, AppDatabase.kt, SongRepository.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/store/SongRepositoryTest.kt` (Robolectric, in-memory DB)

**Interfaces:**
- Consumes: `Lang`, `Alignment`, `LineTiming`, `WordTiming` (core)
- Produces:
  ```kotlin
  @Entity data class SongEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val artist: String,
      val language: Lang, val lyricsText: String, val audioUri: String?, val createdAt: Long)
  @Entity(primaryKeys = ["songId","lineIndex","wordIndex"]) data class TimingEntity(val songId: Long, val lineIndex: Int,
      val wordIndex: Int /* -1 = whole line */, val startMs: Long, val endMs: Long, val matched: Boolean)
  @Entity data class AnalysisEntity(@PrimaryKey val songId: Long, val matchRatio: Double, val modelName: String, val analysedAt: Long)

  enum class SongMode { AUDIO_READY, AUDIO_NOT_ANALYSED, LYRICS_ONLY }
  data class SongSummary(val id: Long, val title: String, val artist: String, val mode: SongMode)

  class SongRepository(db: AppDatabase, uriReadable: (String) -> Boolean) {
      fun observeLibrary(): Flow<List<SongSummary>>
      suspend fun get(id: Long): SongEntity?
      suspend fun upsert(song: SongEntity): Long        // clears timings+analysis if lyrics or audio changed
      suspend fun delete(id: Long)
      suspend fun saveAlignment(songId: Long, alignment: Alignment, modelName: String)  // replaces existing; saves nothing but the Analysis row when alignment.lines is empty
      suspend fun loadTimings(songId: Long): Pair<List<LineTiming>, List<WordTiming>>
      suspend fun analysis(songId: Long): AnalysisEntity?
  }
  ```
- Mode rule: `audioUri == null` or `!uriReadable(uri)` → `LYRICS_ONLY`; audio + timings → `AUDIO_READY`; else `AUDIO_NOT_ANALYSED`.

- [ ] **Step 1: Failing tests:** `insert then observe shows song`, `save and load alignment round trips`, `editing lyrics clears timings`, `song with unreadable audio uri is reported as lyrics only`, `delete removes timings`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*SongRepositoryTest*'` — FAIL.
- [ ] **Step 3:** Implement (TypeConverter for `Lang`). `uriReadable` in production = try `contentResolver.openFileDescriptor(uri,"r")`.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(app): room song store`.

### Task 10: whisper.cpp engine and model manager

**Files:**
- Add submodule: `app/src/main/cpp/whisper.cpp` at tag `v1.7.6` (`git submodule add https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp && git -C app/src/main/cpp/whisper.cpp checkout v1.7.6`)
- Create: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/whisper_jni.cpp`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/speech/{WhisperNative.kt, WhisperCppEngine.kt, ModelManager.kt, ModelSpec.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/speech/ModelManagerTest.kt` (JVM, fake HTTP via `okhttp3.mockwebserver`)
- Test: `app/src/androidTest/kotlin/com/lyricsmachine/app/speech/WhisperCppEngineTest.kt`
- Create: `app/src/androidTest/assets/` — 3 short WAVs (16 kHz mono) **recorded by the developer**: an English spoken phrase, an English sung phrase, a Malay spoken phrase, each using original text listed in a `README.md` beside them.

**Interfaces:**
- Consumes: `SpeechEngine`, `HeardWord`, `Lang` (core)
- Produces:
  ```kotlin
  enum class ModelSpec(val fileName: String, val url: String, val sha256: String, val sizeBytes: Long) {
      BASE("ggml-base-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", "<pin>", <pin>),
      TINY("ggml-tiny-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin", "<pin>", <pin>),
  }
  sealed interface ModelState { data object Missing; data class Downloading(val progress: Float); data class Ready(val file: File); data class Failed(val message: String) }
  class ModelManager(dir: File, http: OkHttpClient) {
      fun state(spec: ModelSpec): StateFlow<ModelState>
      suspend fun ensure(spec: ModelSpec)    // download to .part, verify SHA-256, atomic rename; deletes .part on mismatch → Failed("checksum")
  }
  class WhisperCppEngine(modelFile: File, threads: Int) : SpeechEngine, Closeable
  ```
- Pin `sha256` and `sizeBytes` by downloading each file once and running `sha256sum`; commit the real values (the only values in this plan left to fill at implementation time, because they come from the hosted files).
- JNI: `nativeInit(path): Long`, `nativeTranscribe(ctx: Long, pcm: FloatArray, lang: String?, threads: Int): Array<String>` returning `"t0Ms|t1Ms|p|text"` per word, `nativeFree(ctx)`. Use `whisper_full` with `WHISPER_SAMPLING_GREEDY`, `token_timestamps = true`, `max_len = 1`, `split_on_word = true`, `language = lang ?: "auto"`, `translate = false`, `no_context = true`. Segment times are in 10 ms units → ×10. Guard the context with a mutex; one transcribe at a time.

- [ ] **Step 1: Failing JVM tests:** `downloads and verifies`, `checksum mismatch fails and removes part file`, `ready when file already present with correct hash`, `network error reports Failed and allows retry`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*ModelManagerTest*'` — FAIL.
- [ ] **Step 3:** Implement `ModelManager`.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Write the CMake build (add whisper.cpp as subdirectory, link `whisper` into `lyricsjni`, `-O3`, ABI filters from Global Constraints) and the JNI + `WhisperCppEngine`.
- [ ] **Step 6: Instrumented test:** `transcribes english spoken phrase` (≥ 60% of expected normalised words present), `transcribes malay phrase with ms hint`, `word timestamps are increasing`. Model provisioned by pushing `ggml-tiny-q5_1.bin` to the test app's files dir in `@Before`.
- [ ] **Step 7:** Run `./gradlew :app:connectedDebugAndroidTest --tests '*WhisperCppEngineTest*'` on an arm64 emulator/device — PASS.
- [ ] **Step 8:** Commit `feat(app): whisper.cpp speech engine and model manager`.

### Task 11: AudioDecoder and analysis worker (mode 1)

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/audio/AudioDecoder.kt`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/analysis/{AnalysisWorker.kt, AnalysisRunner.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/analysis/AnalysisRunnerTest.kt`

**Interfaces:**
- Consumes: `AudioMath`, `ChunkedTranscriber`, `SpeechEngine`, `Aligner.alignFull`, `LyricsParser` (core); `SongRepository` (Task 9); `ModelManager` (Task 10)
- Produces:
  ```kotlin
  class AudioDecoder(context: Context) {
      fun durationMs(uri: Uri): Long
      fun decode16kMono(uri: Uri, chunkMs: Long = 30_000): Sequence<FloatArray>   // MediaExtractor + MediaCodec, AudioMath for mix/resample
  }
  sealed interface AnalysisResult { data class Done(val matchRatio: Double, val lowQuality: Boolean); data class Error(val reason: AnalysisError) }
  enum class AnalysisError { AUDIO_UNREADABLE, MODEL_MISSING, CANCELLED, ENGINE_FAILED }
  class AnalysisRunner(repo: SongRepository, engine: SpeechEngine, decode: (String) -> Sequence<FloatArray>, totalSamples: (String) -> Long, modelName: String) {
      suspend fun run(songId: Long, onProgress: (Float) -> Unit): AnalysisResult   // saves alignment only on Done
  }
  object AnalysisWork { fun enqueue(context: Context, songId: Long): UUID; fun observe(context: Context, songId: Long): Flow<AnalysisUiState> }
  data class AnalysisUiState(val progress: Float, val result: AnalysisResult?)
  ```
- `AnalysisWorker` is a `CoroutineWorker` using `setForeground` with a notification "Analysing <title>…"; unique work name `analyse-<songId>` with `REPLACE`.

- [ ] **Step 1: Failing tests** (fake engine, fake decoder): `run saves alignment and reports ratio`, `unreadable audio returns AUDIO_UNREADABLE and keeps song`, `cancellation discards partial results`, `low match ratio reported as lowQuality`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*AnalysisRunnerTest*'` — FAIL.
- [ ] **Step 3:** Implement runner, decoder, worker.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(app): mode 1 analysis pipeline`.

### Task 12: PlaybackSync (mode 1) and LiveSession (mode 2)

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/playback/PlaybackSync.kt`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/live/{MicCapture.kt, LiveSession.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/live/LiveSessionTest.kt`

**Interfaces:**
- Consumes: `TimingLookup`, `LiveTracker`, `RollingBuffer`, `AudioMath`, `PerformanceGuard`, `SpeechEngine` (core)
- Produces:
  ```kotlin
  class PlaybackSync(context: Context, audioUri: Uri, lookup: TimingLookup) : Closeable {
      val position: StateFlow<PlaybackPosition>   // updated ~30 Hz while playing
      val isPlaying: StateFlow<Boolean>
      fun play(); fun pause(); fun seekToLine(lineIndex: Int)
  }
  interface MicSource { fun start(onSamples: (FloatArray) -> Unit); fun stop() }   // MicCapture = AudioRecord 16 kHz mono float
  sealed interface LiveEvent { data object SwitchedToTinyModel : LiveEvent; data object StillTooSlow : LiveEvent }
  class LiveSession(lyrics: Lyrics, mic: MicSource, engineFor: (ModelSpec) -> SpeechEngine, language: Lang?,
                    clock: () -> Long, scope: CoroutineScope, config: TrackerConfig = TrackerConfig()) {
      val snapshot: StateFlow<TrackerSnapshot>
      val events: SharedFlow<LiveEvent>
      fun start(); fun stop(); fun jumpTo(lineIndex: Int)
  }
  ```
- Every `chunkIntervalMs`: take `RollingBuffer.latest()` (`windowMs` of audio), compute `audioPresent = rms > SILENCE_RMS`, transcribe only if present, feed `tracker.onChunk`, record inference time in `PerformanceGuard(budgetMs = chunkIntervalMs)`. If over budget on BASE → swap to TINY and emit `SwitchedToTinyModel`; over budget on TINY → emit `StillTooSlow` once.

- [ ] **Step 1: Failing tests** (fake mic, fake engine with controllable delay, `TestScope` virtual time): `locks after mic hears phrase`, `skips transcription on silence`, `slow engine switches to tiny model`, `still too slow emitted once`, `jumpTo moves snapshot line`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*LiveSessionTest*'` — FAIL.
- [ ] **Step 3:** Implement `LiveSession`, `MicCapture`, `PlaybackSync` (Media3 ExoPlayer; position poll in a coroutine while playing).
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(app): playback sync and live session`.

### Task 13: Library and Add/Edit screens

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/library/{LibraryScreen.kt, LibraryViewModel.kt}`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/edit/{EditSongScreen.kt, EditSongViewModel.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/ui/edit/EditSongViewModelTest.kt`

**Interfaces:**
- Consumes: `SongRepository`, `SongSummary`, `SongMode` (Task 9); `LyricsParser` (core); `AnalysisWork.enqueue` (Task 11)
- Produces: `EditSongViewModel.save(): SaveOutcome` where `enum class SaveOutcome { SAVED_LYRICS_ONLY, SAVED_NEEDS_ANALYSIS, INVALID_EMPTY_LYRICS, INVALID_EMPTY_TITLE }`. After `SAVED_NEEDS_ANALYSIS` navigate to `analyse/{id}`.
- Library: list rows with title, artist, mode badge ("Audio" / "Needs analysis" / "Lyrics only"); FAB "Add song"; long-press menu Edit / Delete (with confirm dialog). Empty state: "No songs yet — tap + to add lyrics."
- Edit: title, artist, language dropdown (English / Bahasa Malaysia / Bahasa Indonesia / Mixed), multiline lyrics field, "Import .txt/.lrc" (`OpenDocument` with `text/*`), "Attach audio" (`OpenDocument` with `audio/*`, then `takePersistableUriPermission`), "Remove audio".

- [ ] **Step 1: Failing tests:** `empty lyrics rejected`, `empty title rejected`, `lyrics only save`, `save with audio needs analysis`, `imported lrc fills lyrics field`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*EditSongViewModelTest*'` — FAIL.
- [ ] **Step 3:** Implement view models and screens.
- [ ] **Step 4:** Run — PASS; manually add one lyrics-only song on the emulator and see it in the library.
- [ ] **Step 5:** Commit `feat(app): library and edit screens`.

### Task 14: Model download gate and Analysing screen

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/setup/ModelSetupScreen.kt`
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/analyse/{AnalyseScreen.kt, AnalyseViewModel.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/ui/analyse/AnalyseViewModelTest.kt`

**Interfaces:**
- Consumes: `ModelManager`, `ModelState` (Task 10); `AnalysisWork`, `AnalysisUiState`, `AnalysisError` (Task 11)
- Produces: user-facing copy (exact):
  - Model setup: "Lyrics Machine needs a one-time download of its speech model (about 60 MB). After that, everything works offline." Buttons "Download" / "Retry".
  - Low quality: "Timing may be rough — check the lyrics match this recording."
  - Audio unreadable: "This audio file can't be opened. The lyrics are saved — you can still sing with the microphone."
  - Model missing: "The speech model isn't downloaded yet."
- Model setup screen shows before Analyse/Sing(mode 2) when `ModelState != Ready`; Library and lyrics viewing never blocked.

- [ ] **Step 1: Failing tests:** `progress mirrors work state`, `done navigates to sing`, `low quality shows warning but allows continue`, `audio unreadable shows message`, `cancel stops work`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*AnalyseViewModelTest*'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(app): model setup and analysis screens`.

### Task 15: Sing screen

**Files:**
- Create: `app/src/main/kotlin/com/lyricsmachine/app/ui/sing/{SingScreen.kt, SingViewModel.kt, LyricsView.kt}`
- Test: `app/src/test/kotlin/com/lyricsmachine/app/ui/sing/SingViewModelTest.kt`

**Interfaces:**
- Consumes: `PlaybackSync`, `LiveSession`, `LiveEvent` (Task 12); `TimingLookup`, `TrackerSnapshot`, `TrackerState` (core); `SongRepository` (Task 9)
- Produces:
  ```kotlin
  data class SingUiState(val lines: List<LyricLine>, val currentLine: Int?, val currentWord: Int?, val wordProgress: Float,
      val mode: SingMode, val isRunning: Boolean, val trackerState: TrackerState?, val message: String?)
  enum class SingMode { PLAYBACK, LIVE, MANUAL }
  class SingViewModel { fun toggle(); fun nudge(delta: Int); fun tapLine(index: Int) }
  ```
- Mode selection: `AUDIO_READY` → PLAYBACK (with a "Use microphone instead" option); `LYRICS_ONLY` or `AUDIO_NOT_ANALYSED` → LIVE; no mic permission or empty timings in PLAYBACK → MANUAL (nudge/tap only) with a message.
- `LyricsView`: current line large, highlighted, words fill left→right by `wordProgress`; previous line faded above; next two lines dimmed; auto-scroll animates to current line; spacer lines render as small gaps/labels. Tablet (width ≥ 600dp) uses larger type. `FLAG_KEEP_SCREEN_ON` while on screen.
- Mic permission: request on entering LIVE; on denial show "Microphone access is needed to follow you live. Tap to allow." and fall back to MANUAL.
- Tracker state chip: Listening / Following / Lost — tap a line to help.
- `LiveEvent.SwitchedToTinyModel` → snackbar "Switched to a faster model for this device." `StillTooSlow` → "This device is struggling — the lyrics may lag."

- [ ] **Step 1: Failing tests:** `audio ready starts in playback mode`, `lyrics only starts in live mode`, `sing screen with no timings allows manual stepping`, `nudge clamps to first and last line`, `tap line seeks playback`, `tap line jumps live tracker`, `denied permission falls back to manual`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*SingViewModelTest*'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat(app): sing screen`.

### Task 16: End-to-end manual check on device

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest` — all PASS.
- [ ] **Step 2:** `./gradlew :app:assembleDebug`, install on the phone and tablet.
- [ ] **Step 3:** Mode 1: add a song with an audio file and its lyrics → analysis completes → highlighted line within ~0.5 s of the singer.
- [ ] **Step 4:** Mode 2: lyrics-only song, music on an external speaker → tracker locks within one or two lines of singing, trails ≤ ~2 s, survives an instrumental break, doesn't jump to an earlier chorus.
- [ ] **Step 5:** Try one English, one Malay and one Indonesian song, plus a mixed-language one. Note any tuning needed in `TrackerConfig` and file it as a follow-up.
- [ ] **Step 6:** Commit any fixes; tag `v0.1.0`.

---

## Amendment A1 — Part B builds on GitHub Actions (2026-10-21)

Part B is built and verified by GitHub Actions, not a local Android Studio, because the authoring environment cannot reach the Android SDK/NDK, GitHub source downloads or Hugging Face. These amendments override the Part B task text where they differ.

- **Task 8 adds CI.** `.github/workflows/android.yml` runs on push and pull_request: checkout, `actions/setup-java@v4` (temurin 17), `android-actions/setup-android@v3`, `sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`, then `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --stacktrace`. It uploads `app/build/outputs/apk/debug/app-debug.apk` as artifact `lyrics-machine-debug-apk`, and on `main` also publishes it as GitHub pre-release `latest-debug` (asset `lyrics-machine.apk`) via `softprops/action-gh-release@v2`, so the user can download it on a phone. `settings.gradle.kts` adds `google()` to both plugin and dependency repositories. The "Step 2 install on emulator" check becomes "CI green and the APK artifact exists".
- **Stable signing.** A dedicated debug keystore `app/lyrics-debug.keystore` (alias `lyricsdebug`, store and key password `lyricsdebug`) is committed and used for the `debug` build type, so new APKs install over old ones. It is for personal sideloading only; release signing is out of scope.
- **Task 10 changes:**
  - whisper.cpp is pulled by CMake `FetchContent` (`GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp`, `GIT_TAG v1.7.6`, `GIT_SHALLOW TRUE`) instead of a git submodule.
  - Model SHA-256 values and sizes are pinned from the Hugging Face API (`https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main`, field `lfs.oid` / `lfs.size`), fetched in a temporary CI step whose output the implementer reads, and then that step is removed.
  - The instrumented `WhisperCppEngineTest` and the developer-recorded WAV assets are dropped from CI (no device, no recordings). Engine behaviour is verified in Task 16 on the user's devices.
- **Task 9 addition (final-review M5, spec §4.1):** core gets `object LrcTimings { fun fromLyrics(lyrics: Lyrics, endMs: Long? = null): List<LineTiming> }`. Each non-spacer line with `lrcStartMs` gets start = its lrcStartMs and end = the next timed line's start (or `endMs`, or start + 4000 ms for the last line). Lines without lrcStartMs get no timing.
  - `SongRepository.loadTimings` falls back to it when no analysis exists.
  - A song with audio and LRC timings but no analysis counts as `AUDIO_READY`.
  - Unit-tested in core.
- **Verification commands** in Part B steps run in CI. An implementer pushes the branch, waits for the workflow run, and reads the result (`gh api repos/kasyrno-cyber/lyrics-machine/actions/runs?branch=<branch>` and the PR's check runs). Local runs are limited to `:core:test`.
