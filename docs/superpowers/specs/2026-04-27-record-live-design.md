# Record Live — design spec

**Date:** 2026-04-27
**Status:** Approved (pending user re-review of written spec)
**Target device:** Samsung Galaxy S24 Ultra (only)

## 1. Goal

Add a `[Record Live]` capability to the existing Whispr app so the user can capture audio with the phone's mic (or a connected Bluetooth / wired mic), get a near-live transcript while recording, and end up with both the audio file and the transcript saved to a user-chosen folder.

The existing file-transcribe path (SAF picker → `AudioDecoder` → `TranscriptionEngine`) is unchanged.

## 2. Decisions log (from brainstorming)

| # | Decision | Rationale |
|---|---|---|
| 1 | Near-live transcription is the default; a per-session toggle disables it | User wants to optionally see text scrolling while recording, without making it mandatory |
| 2 | Always save audio + transcript to a user-chosen SAF folder (picked once, reused) | Audio file is the irreplaceable artifact; user owns the location |
| 3 | Audio format: AAC at native source rate, mono, in `.m4a` | Universal Android support, hardware encoder, ~30 MB/hr at typical built-in-mic rate |
| 4 | Chunking: VAD-aligned (silence ≥ 300 ms) with a 30-s force-fire fallback | Better cuts than fixed windows; force-fire keeps the pipeline progressing |
| 5 | Foreground service typed `microphone` + `PARTIAL_WAKE_LOCK` | Survives screen-off, Doze, Samsung Freecess for 60–90 min lectures |
| 6 | Live recording is local-NPU-only; remote engine stays available for file path | Network unreliability + cost during a lecture is unacceptable |
| 7 | Single Start/Stop, no pause; crash-safe via streaming-friendly format | Pause doubles state-machine surface; orphan recovery instead |
| 8 | New `[Record Live]` button on `HomeScreen` next to `[Pick audio file]`; new `RecordScreen` | Minimal restructuring; both modes equally first-class |
| 9 | `VOICE_RECOGNITION` source + system effects on by default; honour active input route at native rate; settings dropdown to override source and toggle effects | Keeps source choice and effects independent for A/B testing; honest about source bandwidth |

## 3. Architecture (Approach 1 — two parallel pipelines from one mic tap)

```
Mic ──► RecordingService ──► raw 16-bit PCM (native rate)
                                  │
                                  ├──► AacWriter ──► .m4a (native rate, on disk)
                                  │
                                  └──► resample-to-16k ──► VAD chunker
                                                              │
                                                              └──► LocalQnnWhisperEngine
                                                                     │
                                                                     └──► Flow<TranscribeEvent> ──► UI
```

Rejected alternative (Approach 2 — recording produces an AAC file, transcription tails the file from disk): wastes work re-encoding then re-decoding PCM, and tailing a not-yet-finalised MP4 mid-write is finicky. The "tail-the-file" decoupling has no practical benefit here.

**Core principle:** the recording pipeline never dies because of the transcription pipeline. They share a mic stream but failures in transcription do not propagate upstream to AAC writing.

## 4. Components

New code lives under existing subpackages where they fit; one new subpackage `recording/` for the service.

```
audio/
  AudioCapture.kt          NEW   wraps AudioRecord + audio-route monitoring
  Vad.kt                   NEW   energy + zero-crossing voice detector
  ChunkBuilder.kt          NEW   16-kHz buffer; cuts at silences or 30 s; pads to 30 s
  AacWriter.kt             NEW   MediaCodec AAC encoder + MediaMuxer + ADTS sidecar
  AudioDecoder.kt          (unchanged)

engine/
  TranscriptionEngine.kt   MOD   add AudioSource.LiveStream(Flow<FloatArray>)
  local/LocalQnnWhisperEngine.kt
                           MOD   accept LiveStream; reuse per-chunk encode/decode loop

recording/                 NEW subpackage
  RecordingService.kt      foreground service (microphone-typed, wake-locked).
                            Owns AudioCapture. Fans the PCM stream into:
                              (a) AacWriter (always)
                              (b) Resampler→ChunkBuilder→engine (when live ON)
                            Exposes singleton flows: state(), events().
  RecordingState.kt        sealed: Idle / Starting / Recording(...) /
                            Paused(reason) / Stopping / Failure(cause)
  Recording.kt             data class describing a recording on disk
  RecordingsStore.kt       DataStore-backed JSON index of past recordings
                            (cap 200 entries; older auto-evicted from index)

ui/
  HomeScreen.kt            MOD   add [Record Live] button; orphan-recovery card;
                                 list of past recordings
  RecordScreen.kt          NEW   mic level meter, timer, "Transcribe while recording"
                                 toggle, Stop button, scrolling segment list,
                                 route indicator
  RecordViewModel.kt       NEW   talks to RecordingService via singleton flows;
                                 handles SAF folder picker first-run
  SettingsScreen.kt        MOD   Recording section: source preset dropdown,
                                 apply-effects toggle, recordings folder URI

data/
  Settings.kt              MOD   add: recordingsFolderUri, recordWithTranscription,
                                       audioSourcePreset, audioEffectsOn
```

### 4.1 Component responsibilities (one line each)

- **`AudioCapture`** — owns the only `AudioRecord` instance in the process. Monitors `AudioDeviceCallback`; reopens `AudioRecord` on input-route changes. Emits 20 ms PCM frames + RMS level.
- **`Vad`** — frame-by-frame energy + zero-crossing voice detector. Pure Kotlin. Returns `Speech | Silence` per 20 ms frame.
- **`ChunkBuilder`** — accepts continuous 16-kHz mono frames; holds up to 30 s; emits a 30-s zero-padded chunk on (a) silence ≥ 300 ms after detected speech in buffer, or (b) 30-s buffer full. Skips pure-silence buffers. Carries 200 ms of left-context across cuts.
- **`AacWriter`** — encodes AAC-LC at native rate, mono. Writes finalised `.m4a` via `MediaMuxer` plus a hidden ADTS sidecar (`.lecture-...aac`) for crash recovery. Sidecar deleted on clean stop.
- **`RecordingService`** — singleton foreground service. Microphone-typed. Holds a `PARTIAL_WAKE_LOCK`. Posts ongoing notification with duration + level glyph (rebuilt every 5 s).
- **`RecordViewModel`** — observes `RecordingService` flows; handles SAF folder picker; handles permission flow; dedupes one-word overlap between consecutive engine segments.

### 4.2 Engine interface change

```kotlin
sealed interface AudioSource {
    data class Uri(val uri: android.net.Uri) : AudioSource
    data class File(val path: String) : AudioSource
    data class Pcm(val samples: FloatArray) : AudioSource { /* unchanged */ }
    data class LiveStream(val chunks: Flow<FloatArray>) : AudioSource   // NEW
}
```

`LocalQnnWhisperEngine.transcribe()` learns to consume `LiveStream` by skipping the decode step and feeding chunks directly into the existing per-chunk encode/decode loop. Per-chunk timestamps already shift to global file time (commit `8ab2c83`), so live segments emit with absolute lecture-clock times for free.

## 5. Data flow & lifecycle

### 5.1 First-run setup (once, on first tap of `[Record Live]`)

1. Request `RECORD_AUDIO` runtime permission. On permanent denial: card explaining why, button to system settings.
2. Request `POST_NOTIFICATIONS` (Android 13+). On denial: non-blocking toast warning; recording still works.
3. SAF `OpenDocumentTree` for the recordings folder. Persist URI permission with `takePersistableUriPermission`. Save to `AppSettings.recordingsFolderUri`.

Subsequent taps re-prompt only for the missing piece.

### 5.2 Start

```
User taps [Start] on RecordScreen
  → RecordViewModel.start(transcribeLive: Bool)
  → RecordingService.start(transcribeLive, sourcePreset, effectsOn)
    • foreground service (microphone-typed) + wake lock + notification posted
    • AudioCapture.open():
        - picks active input device (AudioManager.getDevices(GET_DEVICES_INPUTS))
        - configures AudioRecord(source=preset, sampleRate=device-native, channel=MONO, encoding=PCM_16BIT)
        - attaches NoiseSuppressor / AutomaticGainControl AudioEffects iff effectsOn && available
        - starts a coroutine reading 20-ms frames; emits to a SharedFlow<PcmFrame>
    • Two consumers on the SharedFlow:
        - writer:        PcmFrame → AacWriter.append()                    [always]
        - transcriber:   PcmFrame → resample-to-16k → ChunkBuilder.feed() [iff transcribeLive]
                         ChunkBuilder emits FloatArray(480_000) → engineFlow
                         engineFlow = LocalQnnWhisperEngine.transcribe(LiveStream(chunks))
                         engine emits TranscribeEvent.Segment → RecordingService.events
  → RecordViewModel collects state + events → RecordUiState → RecordScreen
```

### 5.3 Live UI updates while recording

- `RecordingState.Recording.levelDb` updates ~10 Hz from `AudioCapture` (RMS over 20 ms frames, smoothed).
- `RecordingState.Recording.durMs` ticks every 250 ms from a clock in the service (independent of frame timing).
- `TranscribeEvent.Segment`s prepend to the segment list (newest at top).
- Notification rebuilds every 5 s with `mm:ss` and a level glyph.

### 5.4 Stop

```
User taps [Stop]
  → RecordingService.stop()
    • AudioCapture.close()              (mic stops; SharedFlow cancels; writer flushes tail)
    • AacWriter.finalize()              (writes moov atom; closes muxer; deletes ADTS sidecar)
    • ChunkBuilder.flushTail()          (emits one last partial chunk if buffer has speech)
    • engine flow completes naturally → final TranscribeEvent.Final arrives
    • RecordingsStore.add(Recording(uri, durMs, route, sampleRate, transcriptUri))
    • Wake lock released, notification removed, foreground state cleared
  → RecordViewModel observes state → Idle → navigates back to HomeScreen
```

No `[Discard]` button. The recording always saves; deletion is via the recordings list afterwards or the OS file picker.

### 5.5 Backgrounding

The service keeps running. State + events still flow. `RecordViewModel` re-binds to singleton flows on `onResume`. No state lost; no special handling.

### 5.6 Process death mid-recording

- AAC bytes flushed to disk are recoverable via the ADTS sidecar (Section 6).
- On next launch, `RecordingsStore` knows there's an in-progress entry; the orphan card surfaces it on `HomeScreen`: "Last recording (43:11) wasn't finalised. [Recover & transcribe]."
- Recovery path: remux ADTS sidecar to `.m4a` (no transcoding, instant), then run transcription via the existing file path.

### 5.7 Chunking policy (ChunkBuilder)

- **Cut whenever:** silence ≥ 300 ms appears after any detected speech in the buffer, **or** the buffer reaches 30 s.
- **Don't emit if:** the buffer contains zero speech frames (pure silence).
- **Left-context carryover:** the next chunk's buffer pre-fills with the last 200 ms of the previous chunk's tail, so a word cut in half by a 30-s force-fire appears whole at the start of the next chunk.
- **Tail flush at stop:** if the buffer has any speech, emit it (zero-padded to 30 s) so trailing words are transcribed.
- **Overlap dedupe:** `RecordViewModel` checks if segment N+1's first word equals segment N's last word and drops one. Whisper has no built-in overlap-and-stitch; this is implemented in our layer.

## 6. Crash safety

`MediaMuxer` builds the `moov` atom only on `stop()`. If the process dies mid-recording, the `.m4a` has audio data but no index — most players reject it.

**Solution:** dual-write.

- **`<name>.m4a`** — built by `MediaMuxer`, finalised on stop. The user-facing artifact in the SAF folder.
- **`.<name>.aac`** — raw ADTS frames (a dotfile, hidden), written sample-by-sample. AAC ADTS frames are self-describing — every frame is independently decodable, no index needed.

On clean stop, the ADTS sidecar is deleted. On orphan recovery, the sidecar is the source of truth: a one-pass remux to `.m4a` produces a valid file (no transcoding — just re-wrapping AAC frames into MP4 boxes). Instant on a multi-megabyte file.

## 7. Error handling

| Failure | Detection | Response |
|---|---|---|
| Mic permission revoked mid-recording | `AudioRecord.read()` returns error | Stop service, finalise file, error card with "Re-grant mic permission" link |
| Disk full (SAF write fails) | `IOException` from AAC writer | Stop, surface error card; ADTS sidecar holds whatever made it |
| Audio route changes (BT plug/unplug) | `AudioDeviceCallback.onAudioDevicesAdded/Removed` | `AudioCapture.reopen(newDevice)`; ~50–200 ms gap; route change logged in transcript metadata; no banner |
| NPU engine fails mid-chunk | `TranscribeEvent.Failure` from engine | **Recording continues**; transcriber pipeline stops; UI shows "Live transcription stopped: <err>"; user can run file-based transcription on the saved `.m4a` after stop |
| OOM / engine session corrupted | Catch-all `Throwable` in transcriber consumer | Same as above |
| Service killed by OS | `onDestroy` without explicit stop | Sidecar ADTS intact up to last flushed frame; recovery card on next launch |
| User locks phone | No-op | Wake lock holds CPU; `FOREGROUND_SERVICE_MICROPHONE` keeps mic open |
| Phone call comes in | `AudioFocusRequest` → `AUDIOFOCUS_LOSS_TRANSIENT` | `AudioCapture.pause()`; `AacWriter` stops appending (muxer left open, `moov` not yet finalised); `ChunkBuilder.flushTail()` emits the pre-call partial chunk if it contains speech (same rule as on Stop); state → `Paused(reason="phone call")`; notification reflects pause. On `AUDIOFOCUS_GAIN`: reopen `AudioRecord`, resume appending to the same `.m4a`/sidecar. Gap-marker segment in transcript ("— call interrupted, 1m 47s —") |
| Permanent focus loss (`AUDIOFOCUS_LOSS`) | Same listener | Fall through to Stop; save what we have |
| Bluetooth call hijacks mic | Treated as audio focus loss | Same as phone call |

### 7.1 Edge cases

- **No silence in 30 s.** `ChunkBuilder` force-fires at 30 s exactly; left-context carryover heals the boundary.
- **Lecture is mostly silence.** VAD won't fire on pure-silence buffers; the rolling 30-s window overwrites silent frames; nothing reaches the engine.
- **User taps Stop within seconds of starting.** `flushTail` still emits the partial chunk if any speech detected. Pure silence → empty transcript; file is still saved.
- **Two recordings in quick succession.** `RecordingService` is a singleton with a strict state machine; second start while not Idle is rejected (UI button is disabled in that state, this is defence in depth).
- **BT SCO at 8 kHz.** AAC encoder at 8 kHz mono works (LC profile supports 8–96 kHz). Saved file is 8 kHz mono AAC (~10 MB/hr). Linear-resample 8→16 for the Whisper feed; no bandwidth-extension trick.
- **Settings change mid-recording.** Ignored until next recording. Service reads settings once at start.

## 8. Manifest, settings, build

### 8.1 Manifest additions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-feature android:name="android.hardware.microphone" android:required="true" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<service
    android:name=".recording.RecordingService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

`POST_NOTIFICATIONS` and `WAKE_LOCK` already declared.

### 8.2 `AppSettings` additions

```kotlin
data class AppSettings(
    // ... existing ...
    val recordingsFolderUri: String = "",
    val recordWithTranscription: Boolean = true,
    val audioSourcePreset: AudioSourcePreset = AudioSourcePreset.VOICE_RECOGNITION,
    val audioEffectsOn: Boolean = true,
)

enum class AudioSourcePreset { MIC, VOICE_RECOGNITION, CAMCORDER }
```

`SettingsStore.update {}` and `Preferences.toSettings()` extended for each new key. DataStore returns defaults for missing keys; no migration needed.

### 8.3 `RecordingsStore`

```kotlin
data class Recording(
    val id: String,                  // UUID
    val displayName: String,         // "Recording 2026-04-27 15:30"
    val audioUri: String,            // SAF Uri to the .m4a
    val transcriptUri: String,       // SAF Uri to the .md (empty until transcribed)
    val startedAt: Long,             // epoch ms
    val durationMs: Long,            // 0 while in-progress
    val sampleRate: Int,
    val routeLabel: String,          // "Built-in mic" / "BT: Sony WH-1000XM4"
    val state: State,                // RECORDING / FINALISED / ORPHANED / TRANSCRIBED
)
```

JSON-encoded into DataStore (key `recordings_index`). Cap 200 entries; older auto-evicted from the index (file on disk untouched).

### 8.4 Build changes

- `kotlinx-serialization-json` for `RecordingsStore` JSON.
- No new ABI / NDK changes.
- No new model assets.

## 9. Verification

No test infrastructure exists today. Scaffold `app/src/test/` and `app/src/androidTest/` as part of this work.

### 9.1 Pure-Kotlin unit tests (JVM)

- `VadTest` — synthetic sine, white noise, and a small committed speech-WAV fixture; assert speech/silence decisions, including the 300-ms-silence boundary.
- `ChunkBuilderTest` — emits at silence cuts; force-fires at 30 s; skips pure-silence; left-context carryover; `flushTail` on close.
- `RecordingsStoreTest` — round-trip the index through DataStore (in-memory).
- `AppSettingsSerializationTest` — new fields persist and default correctly.

### 9.2 Instrumented tests

- `AacWriterInstrumentedTest` — write 5 s synthetic PCM → finalise → re-extract via `MediaExtractor`, compare PCM round-trip within tolerance. Kill mid-write → ADTS sidecar remuxes to a valid `.m4a`.
- `AudioCaptureInstrumentedTest` — open mic, capture 1 s, verify frame rate and non-zero level. Skipped on emulator.

### 9.3 Manual acceptance tests

Required (must pass before claiming feature complete):

1. **Happy path, transcribe-on.** Record 2 minutes of speech with screen on. Segments appear within 30 s. File saved. Transcript saved.
2. **Happy path, transcribe-off.** Same with toggle off. File saved. No transcription. No NPU activity in `adb shell dumpsys`.
3. **BT headset.** Connect BT mic. Start recording. Route detected (notification + UI label). 8-kHz path. File saved at 8 kHz.

Targeted regression checks (run as needed):

4. **Long-haul.** Record 60 minutes, screen off, phone in pocket. File ≈ 30 MB. Transcript matches lecture content. No kernel reboots (we use `sustained_high_performance`, not `burst`).
5. **Route switch mid-recording.** Start on BT; unplug; verify silent reopen and continued recording.
6. **Phone call interrupt.** Start; receive call; accept; hang up. Pause + resume. Gap marker in transcript. Saved `.m4a` is a single playable file (with the call duration absent from the audio, not silence-padded).
7. **Process kill.** Start; force-stop the app at 5 min. Orphan card on next launch. Recover button produces a playable `.m4a` and runs transcription.
8. **No mic permission.** Tap Record before granting permission. Verify prompt; deny; verify clear error UI.
9. **Disk full.** Fill SAF folder's underlying storage. Verify error card and that already-flushed audio is recoverable.

## 10. Out of scope (intentionally not included)

- Pause/resume controls.
- Live transcription with the remote engine.
- Multiple concurrent recordings.
- Speaker diarisation.
- Automatic chapter/section detection.
- Cloud sync of recordings.
- Editing transcript inside the app (export-and-edit-elsewhere is the workflow).
- A custom IME / floating overlay (mentioned in CLAUDE.md as a future possibility — not part of this spec).
