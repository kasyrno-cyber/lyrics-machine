# Lyrics Machine — Design Spec

Date: 2026-10-21
Status: Draft, awaiting review

## 1. Purpose

A karaoke-style Android app for personal use. The user loads lyrics they supply
themselves; while a song plays, the app keeps the current lyric line highlighted
and scrolls so the singer can follow along. The app works out timing itself —
the user never marks line start times by hand.

### Success criteria

- Mode 1 (audio file): after one-time analysis, the highlighted line is within
  ~0.5 s of the actual sung line for songs whose lyrics match the recording.
- Mode 2 (room mic): the highlighted line trails the singer by no more than
  ~2 s once tracking has locked on, recovers after instrumental breaks, and does
  not jump to the wrong repeat of a chorus.
- Works fully offline on the user's Android phone and tablet.
- Supports English, Bahasa Malaysia, Bahasa Indonesia, and songs mixing them.

### Assumptions

- Personal/hobby use; not a commercial product in v1.
- The user supplies all lyrics. The app does not bundle or fetch lyrics.
- Brief tracking loss is acceptable if the app recovers on its own and the user
  can correct it with one tap.

## 2. Modes

| Mode | Input | Clock | Mic used? |
|---|---|---|---|
| **1 — Song file** | Audio file + lyrics | Audio playback position + saved timings | No (v1) |
| **2 — Room mic** | Lyrics only; music plays from another source | Live Tracker estimate | Yes |

Mode 2 platform note: Android generally does not let one app capture another
app's playback (and Spotify/YouTube opt out of `AudioPlaybackCapture`). Mode 2
is therefore designed around "the microphone hears the room" — music from an
external speaker/TV, or from the phone's own speaker picked up by the mic.

## 3. Screens

1. **Library** — list of songs; badges: "Audio" (mode 1 ready), "Lyrics only".
   Tap to open Sing screen; long-press to edit/delete.
2. **Add / Edit Song** — title, artist, language (EN / MS / ID / Mixed), lyrics
   (paste/type, one line per lyric line; or import `.txt` / `.lrc`), optional
   audio file picked via Android's document picker.
3. **Analysing** — progress bar and cancel button while mode 1 analysis runs.
   Shows the match-quality warning at the end if quality is low.
4. **Sing** — current line large and highlighted; word-level fill-in when word
   timings exist; next two lines dimmed; previous line faded above. Controls:
   play/pause (mode 1), start/stop listening (mode 2), nudge ◀ ▶ one line, tap
   any line to jump there. Supports portrait and landscape; larger type on
   tablets. Keeps the screen awake while singing.

## 4. Components

Each component has one job and a narrow interface so it can be tested alone.

### 4.1 LyricsParser
- Input: raw text or `.lrc` content.
- Output: `Lyrics(lines: List<LyricLine>)`, each line holding display words and
  normalised tokens (lowercase, punctuation removed, Unicode-normalised).
- `.lrc` timestamps, if present, are kept as initial timings (analysis can
  still refine them).
- Blank lines and section labels like `[Chorus]` are kept as visual spacers but
  excluded from matching.

### 4.2 SpeechEngine (interface)
```kotlin
interface SpeechEngine {
    suspend fun transcribe(pcm16k: FloatArray, language: Lang?): List<HeardWord>
}
data class HeardWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
```
- v1 implementation: `WhisperCppEngine` (whisper.cpp via JNI), multilingual
  model, word-level timestamps enabled.
- Model `base` (quantised ~60 MB) by default; `tiny` (~40 MB) fallback on slow
  devices. Downloaded on first launch with progress + retry; stored in app
  storage; integrity checked by SHA-256.
- Language hint: EN/MS/ID passed to Whisper; "Mixed" lets Whisper auto-detect.
- Future: `CloudEngine` implementing the same interface. No other component
  changes.

### 4.3 Aligner
Pure Kotlin, no Android dependencies.
- `alignFull(lyrics, heard): Alignment` — mode 1. Global sequence alignment
  (Needleman–Wunsch style) between lyric tokens and heard tokens.
  Token similarity = fuzzy score (normalised edit distance, plus join/split
  tolerance so "sayang ku" ≈ "sayangku"). Unmatched lyric words get times
  interpolated from matched neighbours. Output: per-line and per-word start/end
  times plus `matchRatio`.
- `locate(lyrics, heard, window): LocateResult?` — mode 2. Local alignment of
  a short heard snippet within a token window; returns best position and a
  confidence score, or null if below threshold.
- Low-quality rule: `matchRatio < 0.40` → warning to user.

### 4.4 LiveTracker (mode 2)
State: current token position, pace (tokens/sec, smoothed), last confident
match time, state ∈ {Listening, Locked, Coasting, Paused, Lost}.

Loop (every ~2 s; analyses the last ~4 s of mic audio, overlapping):
- Search window: 1 line back to 4 lines ahead of current position.
- **Confident match** (≥ 3 consecutive matching tokens or confidence above
  threshold): jump to matched position, update pace → Locked.
- **No match, audio present**: advance at current pace → Coasting.
- **Silence > ~4 s**: stop advancing → Paused.
- **No confident match for ~10 s**: search whole song, favouring positions
  after the current one (next chorus over earlier ones) → Lost until a match.
- **User tap/nudge**: set position directly; tracker continues from there.
- Timing values are constants in one config object so they can be tuned.

Pure Kotlin core with injected clock and audio/engine, so it's testable without
a device. Mic capture (AudioRecord, 16 kHz mono) is a thin Android wrapper.

### 4.5 PlaybackSync (mode 1)
- Media3 ExoPlayer plays the audio file.
- Polls player position (~30 Hz) and maps it to the current line/word via saved
  timings (binary search).
- Tap a line → seek the player to that line's start time.

### 4.6 SongStore
Room database.
- `Song(id, title, artist, language, lyricsText, audioUri?, createdAt)`
- `Timing(songId, lineIndex, wordIndex?, startMs, endMs)`
- `Analysis(songId, matchRatio, modelName, analysedAt)`
- Audio is referenced by persisted content URI (not copied). If the URI is no
  longer readable, the song falls back to "Lyrics only" and the user is told.

### 4.7 AudioDecoder
- Decodes the picked audio file (MP3/AAC/M4A/WAV/OGG/FLAC as supported by the
  device) to 16 kHz mono float PCM in streaming chunks for analysis.

## 5. Data flow

**Mode 1 analysis**: AudioDecoder → 30 s chunks → SpeechEngine (with chunk time
offset) → all HeardWords → Aligner.alignFull → SongStore (Timings + Analysis).
Runs in a foreground service/WorkManager job so it survives leaving the screen;
cancellable.

**Mode 1 singing**: PlaybackSync(position) → current line/word → Sing screen.

**Mode 2 singing**: Mic → rolling buffer → SpeechEngine → Aligner.locate →
LiveTracker → current line → Sing screen.

## 6. Error handling

| Situation | Behaviour |
|---|---|
| Mic permission denied | Mode 2 shows explanation + button to grant; mode 1 unaffected. |
| Model not downloaded / download fails | Clear message, retry button; lyrics-only viewing still works. |
| Device too slow for live mode (inference time > chunk interval) | Switch to `tiny` model automatically and notify; if still too slow, warn that tracking will lag. |
| Audio file unreadable/unsupported | Error message; song kept as "Lyrics only". |
| Low match ratio after analysis | Warning: "Timing may be rough — check the lyrics match this recording." Song still usable. |
| Analysis interrupted | Partial results discarded; user can restart. |

## 7. Tech stack

- Kotlin, Jetpack Compose, Material 3
- whisper.cpp via JNI (NDK build, arm64-v8a + armeabi-v7a)
- Media3 ExoPlayer (+ MediaExtractor/MediaCodec for decoding)
- Room, Kotlin coroutines/Flow, WorkManager
- minSdk 26 (Android 8.0), targetSdk current
- Gradle with version catalog; two modules:
  - `core` — pure Kotlin/JVM, no Android dependencies: lyrics parsing,
    alignment, live tracking, timing lookup, audio utilities, the
    `SpeechEngine` interface. Fully unit-testable on any JVM.
  - `app` — Android: UI, Room store, whisper.cpp JNI engine, audio decode,
    mic capture, playback, analysis worker. Depends on `core`.

## 8. Testing

- **LyricsParser**: unit tests for plain text, `.lrc`, section labels, blank
  lines, Malay/Indonesian affixes and punctuation.
- **Aligner**: unit tests using original made-up lyrics and synthetic "heard"
  sequences with dropped words, misspellings, split/joined words, extra words,
  and repeated choruses; assert timings and `matchRatio`.
- **LiveTracker**: deterministic simulation tests with a fake clock and scripted
  engine output — locking on, coasting through instrumentals, pausing on
  silence, not jumping to an earlier chorus, recovering when lost, honouring
  user taps.
- **SpeechEngine**: instrumented test on emulator/device with a few short
  self-made recordings (spoken and sung, EN and MS) checking that expected words
  appear.
- **Manual**: user tests on their own phone and tablet with their own songs.

No copyrighted lyrics or audio are committed to the repo; all fixtures are
original.

## 9. Out of scope for v1

- Cloud processing (interface slot exists)
- Fetching lyrics from the internet
- Singing score / pitch grading
- Vocal removal / instrumental generation
- Mic-based correction in mode 1
- iOS
