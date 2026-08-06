# android-whisper / Whispr

On-device Whisper for the Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3
for Galaxy), running entirely on the Hexagon NPU through ONNX Runtime's
QNN execution provider, plus a swappable remote engine for any
OpenAI-compatible `/v1/audio/transcriptions` endpoint.

## Status

**v2 — file transcription + live recording, working end-to-end on real
hardware.** Two entry points:

- **Pick audio file**: SAF picker → on-device or remote transcription
  → live transcript file written alongside the source. Any container
  Android's `MediaCodec` decodes.
- **Record Live**: tap a button, capture from the phone mic or a
  connected Bluetooth / wired headset (Samsung-compat SCO routing),
  AAC-encoded `.m4a` lands in a user-chosen SAF folder, and the
  on-device NPU transcribes alongside as VAD-aligned chunks fire.
  Survives screen-off, Doze, Freecess, and incoming calls (auto pause
  + resume). Crash-safe via an ADTS sidecar that can be remuxed back
  to a valid `.m4a` on next launch if the process is killed.

Verified on a Samsung Galaxy S24 Ultra (SM-S928U1, Android 14) with
the JFK inaugural address (~21 min MP3) and live mic + BT headset
recording on `whisper_large_v3_turbo`.

### Measured performance (S24 Ultra, large-v3-turbo)

Per 30-second audio chunk:

| Stage     | Time      | Notes                                  |
|-----------|-----------|----------------------------------------|
| Mel (CPU) | ~600–1200 ms | Pure-Kotlin 128-bin log-mel; first chunk JITs |
| Encoder (NPU) | ~440 ms | Stable across chunks                  |
| Decoder (NPU) | ~17–18 ms / step | Greedy KV-cache decode loop |
| Decoder total | 1.0–2.7 s | 60–160 tokens depending on speech density |

End-to-end: **~3.5 s wall-clock per 30 s of audio → ≈8.5× faster
than real-time, sustained over 21 minutes (42 chunks).**

### What works

- **Multiple Whisper variants** (tiny, base, small, large-v3-turbo)
  selectable from Settings, downloaded on demand from Qualcomm AI Hub
  S3. Each spec carries its own decoder shape constants
  (`numDecoderLayers / numHeads / dModel`); `LocalQnnWhisperEngine` is
  parametrized over them.
- **Foreground-service download** with sticky notification, partial
  wake lock and HTTP `Range:` resume — survives screen-off, Doze, and
  Samsung Freecess.
- **Streaming audio decode**: `AudioDecoder.streamMonoF32` emits 30-s
  f32 chunks straight off MediaCodec; the engine consumes them
  back-pressure-aware. Memory stays bounded (~12 MB peak) regardless
  of source length.
- **Per-chunk error recovery**: any `OrtException` triggers a session
  close+reopen and a single retry; failures surface as a clean
  `TranscribeEvent.Failure`. Sessions are also recycled every 8 chunks
  preventatively.
- **Live transcript file**, written to disk segment-by-segment as
  text emerges. Lands next to the source file for SAF URIs under
  `primary:Download/…`; falls back to `Download/Whispr/` for
  everything else (Android won't let an app write to arbitrary
  public folders without an explicit folder grant).
- **Remote engine fallback** — POST to any OpenAI-compatible
  `/v1/audio/transcriptions`. Works with OpenAI, faster-whisper-server,
  vLLM, RunPod community templates. (Live recording is on-device only;
  remote is rejected with `IllegalArgumentException` on `LiveStream`
  because per-chunk round-trips over classroom Wi-Fi are unreliable.)
- **Live recording from phone mic / BT / wired headset.**
  `RecordingService` (foreground, `microphone`-typed, `PARTIAL_WAKE_LOCK`)
  owns the only `AudioRecord` and fans the PCM stream into two parallel
  consumers: an AAC writer that always runs, and an opt-in transcription
  pipeline (resample-to-16k → VAD-aligned `ChunkBuilder` → engine). The
  AAC writer's failure can never kill the transcriber and vice versa.
- **Bluetooth SCO recording (Samsung-compat).** Bringing up SCO on
  Samsung firmware needs the full set: `AudioManager.MODE_IN_COMMUNICATION`
  + legacy `startBluetoothSco()` + `setCommunicationDevice()` (API 31+) +
  a `BroadcastReceiver` for `ACTION_SCO_AUDIO_STATE_UPDATED` to confirm
  the link actually carries audio. AudioRecord uses
  `MediaRecorder.AudioSource.VOICE_COMMUNICATION` when the route is BT
  (the user's source preset only applies to built-in mic).
- **VAD-aligned chunking** (`audio/Vad.kt` + `audio/ChunkBuilder.kt`).
  Cuts at silences ≥ 300 ms after detected speech, force-fires at 30 s,
  carries 200 ms left-context across cuts so a word clipped by force-fire
  reappears whole in the next chunk. Pure-silence buffers are skipped.
  Each emitted `TimedChunk` carries its real `startSec`/`durationSec` so
  segment timestamps reflect actual audio time, not chunk index × 30 s.
- **Crash-safe AAC writes.** `AacWriter` dual-writes the user-facing
  `<name>.m4a` (`MediaMuxer`, finalised on stop) and a hidden
  `.<name>.aac` ADTS sidecar. ADTS frames are self-describing — every
  frame is independently decodable — so a hard kill mid-record leaves a
  recoverable file. On orphan recovery, the sidecar is remuxed to a
  fresh `.m4a` in one pass (no transcoding).
- **Audio focus pause/resume.** Phone calls and other transient focus
  losses pause the recording cleanly: `AudioCapture.pause()` halts the
  mic reader, the current `ChunkBuilder` is closed (flushing any
  pre-call partial speech to the engine), the AAC encoder is paused
  with the muxer left open. On `AUDIOFOCUS_GAIN`, a fresh
  `ChunkBuilder` is wired up and the recording continues into the
  same `.m4a`. The recording state machine handles this without
  tearing down the session.
- **Recordings list** on the Home screen surfaces past sessions
  (DataStore-backed JSON index, cap 200, newest first, evicted from
  the index — disk artifacts untouched).
- **Unit + instrumented tests.** Pure-Kotlin tests for `Vad`,
  `ChunkBuilder`, `RecordingsStore`, and `AppSettings` round-trips run
  under `:app:testDebugUnitTest`. `AacWriter` and `AudioCapture` have
  instrumented tests under `:app:connectedDebugAndroidTest`.

## For a follow-up agent (start here)

If you're picking this up cold to build or extend the project:

1. **Read `CLAUDE.md` first** (or `AGENTS.md` — same content, both
   are checked in). It's the source of truth for architectural
   contracts, package layout conventions, and the traps that will
   silently break things (`htp_performance_mode`, fp16 buffers,
   `extractNativeLibs`, Samsung BT SCO, Dual-App install). Every
   trap listed in [Hard-won lessons](#hard-won-lessons) below is
   already re-explained in `CLAUDE.md` with the full context —
   don't rediscover them.
2. **Branch:** `claude/whisper-android-app-icYkl`. All work has
   landed there; `main` is behind. Push follow-up work to the same
   branch or a child of it, and never force-push over other
   sessions' commits.
3. **Live-recording design + plan** live at
   `docs/superpowers/specs/2026-04-27-record-live-design.md` and
   `docs/superpowers/plans/2026-04-27-record-live.md`. Read both
   before touching anything under `recording/` or `audio/`; update
   them if you extend that subsystem.
4. **Smoke test before touching anything** — see
   [Smoke test](#smoke-test-end-to-end-sanity-check) below. If a
   clean checkout doesn't produce a working APK on the device, fix
   that before starting new work.
5. **Priority for next work** is in [Roadmap](#roadmap),
   most-impactful first. The top three (orphan-recovery UI, Silero
   VAD, force-lang-token in the local decoder) are the highest-value
   refinements to the shipped v2 feature set. Ship them one at a
   time, each in its own commit with a clear message.

## Architecture

```
app/src/main/java/dev/pabloi/whisper/
├── audio/
│   ├── AudioDecoder.kt             # Streaming MediaExtractor+MediaCodec → 16k mono f32 chunks
│   ├── AudioCapture.kt             # AudioRecord wrapper: route picking, BT SCO setup,
│   │                                  AGC/NS effects, 20-ms PCM frames, RMS level meter
│   ├── AacWriter.kt                # PCM → AAC-LC + ADTS sidecar for crash recovery
│   ├── Vad.kt                      # Energy + zero-crossing voice detector (pure Kotlin)
│   ├── ChunkBuilder.kt             # 16-kHz buffer, VAD-aligned cuts, 30-s force-fire
│   └── TimedChunk.kt               # Chunk + startSec + durationSec for live segment timestamps
├── engine/
│   ├── TranscriptionEngine.kt      # Stable interface + AudioSource/Options/Event
│   │                                  (incl. AudioSource.LiveStream for the recording path)
│   ├── EngineFactory.kt            # Picks engine from settings
│   ├── local/
│   │   ├── ModelCatalog.kt         # ModelSpec rows for tiny/base/small/turbo
│   │   ├── ModelRepository.kt      # Per-spec resumable download
│   │   ├── ModelDownloadService.kt # Foreground service + wake lock + notification
│   │   ├── MelSpectrogram.kt       # Whisper-v3 log-mel (128 bins, CPU)
│   │   ├── HalfFloat.kt            # IEEE 754 binary16 ↔ binary32 (no JDK 20+)
│   │   ├── WhisperTokenizer.kt     # GPT-2 byte-level decoder over tokenizer.json
│   │   └── LocalQnnWhisperEngine.kt # ORT+QNN encoder + greedy KV-cache decode (fp16)
│   └── remote/
│       └── RemoteOpenAIEngine.kt   # OkHttp multipart to /v1/audio/transcriptions
├── recording/                      # Live-recording subsystem
│   ├── RecordingService.kt         # Foreground microphone-typed service: owns AudioCapture,
│   │                                  fans PCM into AacWriter (always) + ChunkBuilder→engine
│   │                                  (opt-in), AudioFocus pause/resume, wake lock
│   ├── RecordingState.kt           # Idle / Starting / Recording / Paused / Stopping / Failure
│   ├── Recording.kt                # @Serializable record-of-record (path, dur, route, state)
│   └── RecordingsStore.kt          # DataStore JSON index of past recordings (cap 200)
├── data/
│   ├── Settings.kt                 # DataStore-backed preferences (incl. recording knobs)
│   └── TranscriptWriter.kt         # MediaStore.Downloads streaming writer
├── ui/                             # Compose screens + ViewModels
│   ├── HomeScreen.kt               # File-pick + Record Live entry points + recordings list
│   ├── RecordScreen.kt             # Live recording UI: meter, timer, segments, perm prompts
│   ├── RecordViewModel.kt          # Observes RecordingService flows, dedupes overlap
│   ├── HomeViewModel.kt
│   └── SettingsScreen.kt           # Engine/api/lang/timestamps + Recording section
├── MainActivity.kt                 # NavHost: home / record / settings
└── WhisprApp.kt
```

The `TranscriptionEngine` interface is the contract both engines
satisfy; UI talks to it through `EngineFactory`. A future on-device
HTTP server (for an IME or other processes to consume the local
engine) plugs into the same interface. Live recording reuses the
engine via the `AudioSource.LiveStream(Flow<TimedChunk>)` variant —
chunks flow straight from `ChunkBuilder` into the existing per-chunk
encode/decode loop without round-tripping through MediaCodec.

## Models

The app **does not bundle weights**. On first selection of the
on-device engine for a given variant, the app downloads Qualcomm's
pre-compiled QNN-ONNX bundle for `qualcomm-snapdragon-8gen3` from S3,
plus the matching tokenizer from Hugging Face. URLs and decoder
shape constants are hard-coded in `ModelCatalog.kt`.

| Model                  | Size on disk | URL fragment                                     |
|------------------------|-------------:|--------------------------------------------------|
| `whisper_tiny`         | ~120 MB     | `qaihub-public-assets.../whisper_tiny/...`        |
| `whisper_base`         | ~210 MB     | `qaihub-public-assets.../whisper_base/...`        |
| `whisper_small`        | ~700 MB     | `qaihub-public-assets.../whisper_small/...`       |
| `whisper_large_v3_turbo` | ~1.8 GB    | `qaihub-public-assets.../whisper_large_v3_turbo/...` |

Stored under `Context.filesDir/models/<spec.id>/`.

## Build

Prerequisites:
- Android SDK 35 with NDK r26+
- JDK 17
- `local.properties` with `sdk.dir=...` (forward slashes on Windows;
  Java properties parsing eats `\U`, `\p`, etc., silently)

```
./gradlew :app:assembleDebug
adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` matters on Samsung phones with the **Dual App** feature
enabled — without it, every install is auto-mirrored to the secondary
user (`DUAL_APP`, uid 95) and you end up with two icons on the
launcher sharing nothing. `--user 0` keeps the install scoped to the
primary user.

The Gradle config restricts native libs to `arm64-v8a`. **Keep
`jniLibs.useLegacyPackaging = true`** — QNN's fastrpc DSP loader
requires the `*Skel.so` files to live at a real on-disk path, and
the modern `extractNativeLibs="false"` default mmap's them inside the
APK where the DSP can't dlopen them.

### Tests

```
./gradlew :app:testDebugUnitTest         # JVM unit tests (Vad, ChunkBuilder, RecordingsStore, Settings)
./gradlew :app:connectedDebugAndroidTest # AacWriter + AudioCapture instrumented (real device)
```

### Smoke test (end-to-end sanity check)

Before starting new work, verify the current build actually runs on
the device. Type checking is not enough — QNN session load, model
download, and audio routing all only fail at runtime.

1. `./gradlew :app:testDebugUnitTest` → passes.
2. `./gradlew :app:assembleDebug` → APK builds.
3. `adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk`
   → installs cleanly (no `INSTALL_FAILED_*`).
4. Launch the app; Settings → pick `whisper_tiny` (smallest download,
   ~120 MB) and hit Download. Sticky notification appears; download
   completes even with screen off.
5. Home → Pick audio file → any short `.m4a`/`.wav`. Segments should
   appear within a few seconds; final transcript writes to
   `Download/Whispr/…-transcript.txt`.
6. Home → Record Live → Start. Level meter moves; stop after ~10 s.
   `.m4a` lands in the chosen SAF folder; live segments appeared while
   recording.
7. `adb logcat -s LocalQnnWhisperEngine RecordingService AudioCapture`
   → no `Failure`, no `IllegalStateException`, no `OrtException`.

If any step fails, that's the first thing to fix.

### Signing for release

Add to `~/.gradle/gradle.properties`:

```
WHISPR_KEYSTORE=/abs/path/to/keystore.jks
WHISPR_KEYSTORE_PASSWORD=...
WHISPR_KEY_ALIAS=...
WHISPR_KEY_PASSWORD=...
```

If unset, release builds are unsigned (use `assembleDebug` for
side-loading during development).

## Engine selection

Settings → Engine:
- **On-device NPU** — runs locally on Hexagon. Pick a Whisper variant
  in the same screen.
- **Remote** — POST to a configurable OpenAI-compatible endpoint.
  Works out of the box with OpenAI's API, faster-whisper-server,
  vLLM, and most RunPod community templates that expose
  `/v1/audio/transcriptions`. Set base URL and an API key.

## Hard-won lessons

These are the traps we've already paid for; see `CLAUDE.md` for full
detail.

- **`htp_performance_mode = "burst"` reboots the device** under
  sustained load. Burst mode forces adsprpc into 10 ms busy-polling;
  Whisper's encoder takes ~480 ms per call, blows the timeout
  repeatedly, and eventually trips a kernel `Oops PC =
  plist_add+0x80/0x12c` plus the TrustZone non-secure watchdog →
  full SoC reboot. Use `sustained_high_performance`.
- **ORT 1.23 can't parse Qualcomm's IR v12 bundles.** Pin to
  `onnxruntime = "1.24.3"` (matches the `tool_versions.onnx_runtime`
  in `release_assets.json`).
- **QNN inputs are fp16, not fp32.** All decoder/encoder float
  tensors are `OnnxJavaType.FLOAT16` backed by `ShortBuffer`s
  carrying the raw fp16 bit pattern. `HalfFloat` does the conversion;
  the JVM doesn't have an fp16 primitive.
- **Don't use `extractNativeLibs="false"`** for QNN. The DSP fastrpc
  loader can't dlopen libs from inside an APK.
- **`<uses-native-library>` declarations** are required on Android
  12+ for `libcdsprpc.so`, `libadsprpc.so`, etc. — without them, the
  app's namespace can't see vendor libs.
- **Java heap on Android is per-app capped** (~256–512 MB on Samsung).
  21 min of f32 PCM is 80 MB on its own; an `ArrayList<Short>` for
  decoded PCM autoboxes into hundreds of MB. Stream chunks; don't
  materialize.
- **Samsung BT SCO needs the full compat dance.**
  `setCommunicationDevice(btScoDevice)` and the
  `OnCommunicationDeviceChangedListener` confirmation only update the
  routing pointer — on Samsung firmware, the actual SCO audio stream
  doesn't open unless you ALSO set `MODE_IN_COMMUNICATION` and call
  legacy `startBluetoothSco()`, AND you wait for
  `ACTION_SCO_AUDIO_STATE_UPDATED → SCO_AUDIO_STATE_CONNECTED` before
  starting AudioRecord. Plus `AudioRecord.AudioSource` must be
  `VOICE_COMMUNICATION` (not `VOICE_RECOGNITION`) when the route is BT
  — otherwise the OS happily falls through to built-in mic and your
  `.m4a` is silent. `BLUETOOTH_CONNECT` is a runtime permission on
  Android 12+; it must be granted before any of this works.
- **`AudioDeviceCallback.onAudioDevicesAdded` fires synchronously at
  registration** with the current device list. A naïve "always reopen
  on add/remove" callback will tear down the just-started AudioRecord
  immediately. `AudioCapture.shouldReopenOn` filters by whether the
  active device or a higher-priority headset actually appeared.
- **`MutableSharedFlow` with `DROP_OLDEST` silently drops audio** when
  a downstream stalls. For lecture-recording the right policy is
  `SUSPEND` so back-pressure becomes a visible silent meter, not
  invisible data loss.
- **Samsung Dual App** mirrors any new install to user 95 by default,
  giving you two icons on the launcher. Use `adb install -r --user 0
  ...` to install only to the primary user.

## Roadmap

Prioritized most-impactful first. The top three are the natural
next batch of work; the rest are longer-horizon.

### Refinements to shipped features

1. **Orphan-recovery UI.** `RecordingsStore` already tracks
   `Recording.State.RECORDING` / `ORPHANED` entries; on next launch
   after a process kill, surface a "Recover & transcribe" affordance
   that remuxes the ADTS sidecar into a fresh `.m4a` and runs
   file-based transcription on it. Today, orphans appear in the
   recordings list but with no recovery button. Small, self-contained,
   directly rescues data users would otherwise lose. **Start here.**
2. **Silero VAD in place of `Vad.kt`.** Current VAD is an
   energy + zero-crossing heuristic tuned for `VOICE_RECOGNITION`
   with AGC on; it under-detects speech on telephony-bandwidth (BT
   SCO 8 kHz) audio and over-detects on line noise. Silero VAD is a
   ~2 MB ONNX model that runs comfortably on CPU. Drop it into
   `audio/`, feed 20-ms frames, gate `ChunkBuilder`'s speech-active
   decision on it. Big quality win for the live-recording path.
3. **Force language / task token prefixes** in the local engine
   decode loop. The QNN-compiled decoder emits its own language
   token today; the language hint in Settings is silently ignored on
   the local path. Insert the seed sequence
   `[SOT, <|lang|>, <|task|>, <|notimestamps|>]` before greedy
   decode starts and run the graph once per seed token to prime the
   self-attention KV cache. Improves accuracy on multilingual audio.

### Perf & accuracy

- **NDK port of the mel FFT.** Mel is now ~1.2 s the first chunk
  and ~600 ms steady-state on CPU — ~25 % of total wall-clock.
  Inner FFT (currently a direct DFT with a precomputed twiddle
  matrix) is the hot loop; everything else is cheap. A radix-mixed
  or Bluestein FFT in C++ via JNI would drop it to <100 ms.
- **Beam search / temperature ladder / no-speech-prob fallback**
  in the local engine. Current decoder is pure greedy argmax;
  matches reference Whisper only on clean audio. Reference Whisper
  temperature ladder: retry with increasing temp on
  no-speech-prob > 0.6 or compression ratio > 2.4.
- **Whisper `<|0.00|>` timestamp tokens.** Engine currently emits
  per-chunk bounds from `TimedChunk`; enabling Whisper's internal
  segment timestamps gives finer granularity within each 30-s chunk.

### UI

- **Audio device picker UI.** Settings → Recording exposes the source
  preset and effects toggle, but the active input device is auto-picked.
  A manual override (drop-down listing connected mics) would help
  reproducible testing of the BT vs built-in paths.
- **Transcript editor.** Segments are read-only; users may want to
  fix a hallucination inline before sharing.

### Longer-horizon

- **On-device HTTP server** wrapping `LocalQnnWhisperEngine` so an
  IME (or Tasker, or any other process) can call into the local
  engine without launching the GUI. This is what enables the
  Whisper-as-keyboard vision. Plug it in behind
  `TranscriptionEngine` so the UI stays engine-agnostic.
- **IME (soft-keyboard) built on the local HTTP server.**
- **Diarization** (who-said-what). Requires a separate speaker
  embedding model; consider a pyannote export or a lightweight
  ResNet embedding + clustering pass.

## Licensing

- App code: this repository's license.
- Whisper weights / Qualcomm-compiled artifacts: see Qualcomm's
  `Whisper-*` model cards on Hugging Face.
- Whisper tokenizer.json: from `openai/whisper-*` (MIT-equivalent).
