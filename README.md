# android-whisper

On-device Whisper Large v3 Turbo for the Samsung Galaxy S24 Ultra
(Snapdragon 8 Gen 3 for Galaxy), running entirely on the Hexagon NPU
through ONNX Runtime's QNN execution provider, plus a swappable
remote engine for any OpenAI-compatible `/v1/audio/transcriptions`
endpoint.

## Status

**v1 — file transcription only.** Pick an audio file (`.wav`, `.m4a`,
`.mp3`, `.flac`, `.ogg/.opus`, `.webm`, anything Android's
`MediaCodec` decodes), transcribe, copy/share the text. Settings let
you switch between on-device NPU and a remote endpoint.

The local-engine code path is implemented end-to-end — audio decode,
mel spectrogram, ORT+QNN session creation, KV-cache decode loop,
tokenizer — but it has only been verified to **compile and configure**
in this environment. The encoder/decoder ONNX I/O contract is taken
verbatim from `qualcomm/ai-hub-models` (`hf_whisper/model.py`); see
the docstring at the top of `LocalQnnWhisperEngine.kt` for the
tensor layout the code assumes. First on-device run will validate
the contract and surface any mismatch.

## Architecture

```
app/src/main/java/dev/pabloi/whisper/
├── audio/AudioDecoder.kt           # MediaExtractor+MediaCodec → 16k mono f32
├── engine/
│   ├── TranscriptionEngine.kt      # Stable interface + AudioSource/Options/Event
│   ├── EngineFactory.kt            # Picks engine from settings
│   ├── local/
│   │   ├── ModelRepository.kt      # Resumable download of QNN-ONNX assets
│   │   ├── MelSpectrogram.kt       # Whisper-v3 log-mel (128 bins)
│   │   ├── WhisperTokenizer.kt     # GPT-2 byte-level decoder over tokenizer.json
│   │   └── LocalQnnWhisperEngine.kt # ORT+QNN encoder + greedy KV-cache decode
│   └── remote/
│       └── RemoteOpenAIEngine.kt    # OkHttp multipart to /v1/audio/transcriptions
├── data/Settings.kt                # DataStore-backed preferences
├── ui/                             # Compose screens + ViewModel
├── MainActivity.kt
└── WhisprApp.kt
```

The `TranscriptionEngine` interface is the contract both engines
satisfy. A future on-device HTTP server (for an IME or other
processes to consume the local engine) plugs into the same
interface — UI code wouldn't need to change.

## Models

The app **does not bundle any weights** in the APK. On first launch,
selecting the on-device engine prompts a one-time download of
Qualcomm's pre-compiled QNN-ONNX bundle for Snapdragon 8 Gen 3:

```
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/.../v0.51.0/
  whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip
```

Resolved from Qualcomm's
[`release_assets.json`](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo/raw/main/release_assets.json)
on 2026-04. About **1.6 GB** zipped, expanding to ~1.6 GB on disk
under `Context.filesDir/models/whisper_large_v3_turbo_qnn/`. The
download is resumable across crashes (HTTP `Range:`).

Tokenizer comes from
`https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/tokenizer.json`
— a few MB.

## Build

Prerequisites:
- Android SDK 35 with NDK r26+
- JDK 17
- Set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`)

```
./gradlew :app:assembleRelease
# debug build:
./gradlew :app:assembleDebug
```

APK lands in `app/build/outputs/apk/release/`. The Gradle config
restricts native libs to `arm64-v8a` only, since the target device
is the only one we care about.

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

## On-device install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch with the on-device engine selected will show a
"Download model" card; tap it once and the app caches the assets
forever after.

## Engine selection

Settings → Engine:
- **On-device NPU** — runs locally on Hexagon. Needs the model
  installed.
- **Remote** — POSTs to a configurable OpenAI-compatible endpoint.
  Works out of the box with OpenAI's API, faster-whisper-server,
  vLLM, and most RunPod community templates that expose
  `/v1/audio/transcriptions`. Set base URL (e.g. `https://api.openai.com`)
  and an API key.

## Performance expectations on S24 Ultra

From Qualcomm's published benchmarks for Whisper Large v3 Turbo on
Snapdragon 8 Gen 3 (`PRECOMPILED_QNN_ONNX`, NPU):

| Stage   | Time/call | Memory |
|---------|-----------|--------|
| Encoder | 426 ms    | 63–74 MB |
| Decoder | 7.6 ms / token | 43–54 MB |

For a 30-second audio chunk emitting ~50 tokens, end-to-end NPU
work is ~810 ms. The mel computation (CPU, pure Kotlin) currently
adds ~0.5–1 s per chunk; if it becomes a bottleneck, port the
inner FFT to NDK or use a mixed-radix implementation.

## Known limitations / next steps

- **No on-device microphone capture yet.** v1 is file-only; live
  recording lands when the IME path is built.
- **Greedy decoding only.** No beam search, no temperature fallback
  on no-speech-prob — Whisper's reference uses a temperature ladder
  and length penalties; we run pure argmax. Adequate for clear audio,
  may hallucinate on silence/noise.
- **Language and task tokens are model-emitted.** Setting a
  language hint in Settings is forwarded to the remote engine; the
  local engine currently ignores it (the QNN-compiled decoder
  doesn't expose a clean way to force a token without running the
  graph for it). Force-prefix sequencing is a small follow-up.
- **No diarization, no streaming** — both planned.
- **ORT QNN AAR** is pinned to `1.23.0`; Qualcomm built the assets
  against `1.24.3` (not yet on Maven Central). The QAIRT runtime
  shipped with `1.23.0` should load the `.bin`, but if loading
  fails on first run, bumping `onnxruntime` in
  `gradle/libs.versions.toml` once `1.24.x` lands is the fix.

## Licensing

- App code: this repository's license.
- Whisper weights / Qualcomm-compiled artifacts: see Qualcomm's
  `Whisper-Large-V3-Turbo` model card on Hugging Face.
- Whisper tokenizer.json: from `openai/whisper-large-v3-turbo`
  (MIT-equivalent).
