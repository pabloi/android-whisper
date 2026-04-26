# android-whisper / Whispr

On-device Whisper for the Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3
for Galaxy), running entirely on the Hexagon NPU through ONNX Runtime's
QNN execution provider, plus a swappable remote engine for any
OpenAI-compatible `/v1/audio/transcriptions` endpoint.

## Status

**v1 — file transcription, working end-to-end on real hardware.**
Pick an audio file (`.wav`, `.m4a`, `.mp3`, `.flac`, `.ogg/.opus`,
`.webm`, anything Android's `MediaCodec` decodes), transcribe on-device,
get a saved transcript file alongside the source.

Verified on a Samsung Galaxy S24 Ultra (SM-S928U1, Android 14) with
the JFK inaugural address (~21 minutes, MP3) running on
`whisper_large_v3_turbo` against the Hexagon NPU.

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
  vLLM, RunPod community templates.

## Architecture

```
app/src/main/java/dev/pabloi/whisper/
├── audio/AudioDecoder.kt           # Streaming MediaExtractor+MediaCodec → 16k mono f32 chunks
├── engine/
│   ├── TranscriptionEngine.kt      # Stable interface + AudioSource/Options/Event
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
│       └── RemoteOpenAIEngine.kt    # OkHttp multipart to /v1/audio/transcriptions
├── data/
│   ├── Settings.kt                 # DataStore-backed preferences
│   └── TranscriptWriter.kt         # MediaStore.Downloads streaming writer
├── ui/                             # Compose screens + ViewModel
├── MainActivity.kt
└── WhisprApp.kt
```

The `TranscriptionEngine` interface is the contract both engines
satisfy; UI talks to it through `EngineFactory`. A future on-device
HTTP server (for an IME or other processes to consume the local
engine) plugs into the same interface.

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
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle config restricts native libs to `arm64-v8a`. **Keep
`jniLibs.useLegacyPackaging = true`** — QNN's fastrpc DSP loader
requires the `*Skel.so` files to live at a real on-disk path, and
the modern `extractNativeLibs="false"` default mmap's them inside the
APK where the DSP can't dlopen them.

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

## Future work

### Streaming + recording mode (lectures, meetings)

Idea: hit a record button, the app starts capturing from the mic
*and* transcribing simultaneously, with results appearing as text
shortly after each utterance. Output is a live transcript file that
keeps growing while the recording is ongoing.

Open design questions to think through before implementing:

- **Window size + overlap.** The current path processes fixed,
  non-overlapping 30 s windows — fine for files but produces a lag of
  one full window before a phrase appears. Possible alternatives:
  - Smaller fixed windows (e.g. 10 s). Trades latency for accuracy:
    Whisper was trained on 30 s; shorter windows lose context.
  - Sliding window: process the trailing 10 s of audio every 1–2 s,
    then stitch deduplicated tokens. Closer to real-time at the cost
    of redundant compute (each second of audio gets transcribed ~5–10
    times). Stitching is fiddly — Whisper output isn't word-aligned.
  - Hybrid: short (10 s) for live preview that gets revised, then
    a 30 s pass produces the canonical transcript that overwrites the
    preview.
- **Voice-activity detection.** Don't burn NPU on silence; trigger a
  decode pass on speech end + brief silence. WebRTC VAD or Silero VAD
  ports both fit the bill.
- **Audio capture.** `AudioRecord` with 16 kHz mono PCM16 → reuse
  the existing `AudioSource.Pcm` path. Foreground service for
  long-running recording (similar shape to `ModelDownloadService`),
  with a sticky notification.
- **Backpressure.** If the NPU can't keep up (small model: yes,
  large-v3-turbo at 8.5× real-time: yes), capture and inference run
  comfortably in lockstep. If we ever fall behind, we drop oldest
  pending audio rather than memory-grow.
- **UX.** Live partial transcript on screen, scrolling, with timestamps
  relative to recording start. Pause/resume/stop. Auto-save to the
  same `Download/Whispr/` location as file mode.

This is a meaningful chunk of work — 2–3 days of design + build —
but the engine pieces are mostly already there: the streaming
chunk path, the foreground service pattern, the segment writer.

### Other follow-ups

- **Beam search / temperature ladder / no-speech-prob fallback** in
  the local engine. Current decoder is pure greedy argmax; matches
  reference Whisper only on clean audio.
- **NDK port of the mel FFT.** Mel is now ~1.2 s the first chunk
  and ~600 ms steady-state on CPU — ~25 % of total wall-clock.
  Inner FFT is the hot loop; everything else is cheap.
- **Whisper timestamp tokens.** Engine currently uses chunk-window
  bounds (`cIdx * 30s`); enabling Whisper's `<|0.00|>`-style segment
  timestamps gives finer granularity within each chunk.
- **Force language / task token prefixes** in the local engine
  decode loop. The QNN-compiled decoder doesn't auto-pick up the
  setting; needs explicit token sequencing in the first decoder
  step.
- **Diarization** (who-said-what).
- **On-device HTTP server** so an IME / other apps can call into the
  local engine without the GUI.

## Licensing

- App code: this repository's license.
- Whisper weights / Qualcomm-compiled artifacts: see Qualcomm's
  `Whisper-*` model cards on Hugging Face.
- Whisper tokenizer.json: from `openai/whisper-*` (MIT-equivalent).
