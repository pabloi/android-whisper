# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

```
./gradlew :app:assembleDebug         # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease       # release APK; unsigned unless WHISPR_* gradle props set
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Android SDK 35, NDK r26+, JDK 17. No test sources exist yet (`app/src/test/` and `app/src/androidTest/` are absent), so there is no `:app:test` story to follow — when adding tests, scaffold the directory first.

Release signing reads `WHISPR_KEYSTORE` / `WHISPR_KEYSTORE_PASSWORD` / `WHISPR_KEY_ALIAS` / `WHISPR_KEY_PASSWORD` from `~/.gradle/gradle.properties`. Without them, release builds produce an unsigned APK silently — `assembleDebug` is the side-loading path during development.

## Target device & ABI

The app targets exactly one device: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3 for Galaxy). `app/build.gradle.kts` pins `abiFilters += "arm64-v8a"`, so do not add other ABIs without a deliberate reason — it would inflate the APK with unused QNN runtime libs.

## Architecture

Two implementations sit behind a single interface, `engine/TranscriptionEngine.kt`:

- `engine/local/LocalQnnWhisperEngine.kt` — Whisper-Large-v3-Turbo on the Hexagon NPU via ONNX Runtime's QNN execution provider, loading Qualcomm's pre-compiled QNN-ONNX bundle.
- `engine/remote/RemoteOpenAIEngine.kt` — OkHttp multipart POST to any OpenAI-compatible `/v1/audio/transcriptions` endpoint.

`engine/EngineFactory.kt` selects between them from `data/Settings.kt` (DataStore-backed `AppSettings`). UI (`ui/HomeScreen.kt`, `ui/SettingsScreen.kt`, `ui/HomeViewModel.kt`) talks only to `TranscriptionEngine` — adding a third backend (e.g. a local HTTP server wrapping the QNN engine for an IME) means implementing the interface and wiring it through the factory. Do not leak engine-specific concepts into the UI layer.

Audio path is shared: `audio/AudioDecoder.kt` uses `MediaExtractor`+`MediaCodec` to turn whatever the user picks (SAF `Uri` or file path) into 16 kHz mono f32 before any model code runs. `AudioSource.Pcm` exists so future tests/streaming can skip the decode step.

### Local engine I/O contract — read this before touching it

The encoder/decoder tensor layout in `LocalQnnWhisperEngine.kt` is taken **verbatim** from `qualcomm/ai-hub-models` `hf_whisper/model.py`. The docstring at the top of that file enumerates every tensor name, shape, and dtype the code assumes. Changing this contract requires regenerating Qualcomm's compiled assets, not editing the Kotlin — if shapes don't match at runtime, the cause is almost always a model-version drift, not a bug in the decode loop.

Decoding is **greedy argmax with KV-cache** over a fixed `meanDecodeLen = 200`. No beam search, no temperature fallback, no length penalty. The self-attention mask starts fully masked (`-100f`) and is unmasked one slot per step from the right. Cross-attention KV is computed once by the encoder and reused.

`engine/local/MelSpectrogram.kt` is a pure-Kotlin 128-bin log-mel running on the CPU (~0.5–1 s per 30 s chunk). If profiling shows it dominating, the inner FFT is the place to port to NDK — don't move other parts to native first.

### Models are not bundled

`engine/local/ModelCatalog.kt` lists the four Whisper variants Qualcomm publishes precompiled QNN-ONNX bundles for (tiny, base, small, large-v3-turbo). `engine/local/ModelRepository.kt` is per-spec — one instance per `ModelSpec`, each rooted at `Context.filesDir/models/<spec.id>/`. `WhisprApp.modelRepos` is the `Map<String, ModelRepository>` the UI reads; the active model is `AppSettings.localModelId` (defaults to `whisper_large_v3_turbo`). Downloads are resumable via HTTP `Range:` and run inside `ModelDownloadService` — a foreground service holding `PARTIAL_WAKE_LOCK` so the call survives screen-off / Doze / Samsung Freecess. The `.onnx` files reference their sibling `_qairt_context.bin` by relative name, so both must stay co-located on disk. Tokenizer (`tokenizer.json`) is fetched separately from each variant's `openai/whisper-*` HF repo.

### Don't use `htp_performance_mode = "burst"` for long inputs

Setting QNN HTP to `burst` mode forces the Hexagon adsprpc kernel driver into busy-poll with a 10 ms RPC timeout. Whisper-large-v3-turbo's encoder takes ~480 ms per chunk on the S24 Ultra — every encoder run blows the timeout, and over many back-to-back chunks (~21 min audio = ~42 chunks) a race in adsprpc's priority-list management triggers a kernel `Oops PC = plist_add+0x80/0x12c`, the TrustZone non-secure watchdog (`TZBSP_ERR_FATAL_NON_SECURE_WDT`) fires, and the SoC fully reboots. Use `sustained_high_performance` instead — pins the DSP at a steady state without the busy-poll path. `LocalQnnWhisperEngine` also defensively recreates encoder/decoder sessions every 8 chunks to bound any other long-run state accumulation in the QNN context, and per-chunk failures fall back through `runChunkWithRecovery` (close+reopen sessions, retry once, then surface `TranscribeEvent.Failure`).

### ORT / QNN version pinning

`gradle/libs.versions.toml` pins `onnxruntime = "1.24.3"` to match the exact version Qualcomm used to compile the v0.51.0 `precompiled_qnn_onnx` assets (see `tool_versions` in each model's `release_assets.json` on Hugging Face). The `.onnx` wrappers carry ONNX IR v12, which 1.23.x rejects at session creation with "unsupported model IR version: 12, max supported IR version: 11". Stay in lockstep with whatever ORT version Qualcomm publishes against next; mismatches surface as a context-load error, not at runtime.

## Conventions

- Package root: `dev.pabloi.whisper`. Keep new code under existing subpackages (`audio/`, `engine/`, `engine/local/`, `engine/remote/`, `data/`, `ui/`) rather than introducing parallel hierarchies.
- Compose + Material 3 + Navigation Compose; Kotlin 2.1 with the Compose compiler plugin (no kapt/KSP in use). State is hoisted into `HomeViewModel`; screens are stateless.
- Coroutines `Flow` is the cross-layer event channel. `TranscriptionEngine.transcribe` returns a `Flow<TranscribeEvent>` (`Progress` / `Segment` / `Final` / `Failure`) — preserve that surface for new engines so the UI doesn't fork.
- No `READ_EXTERNAL_STORAGE`. Files only enter via SAF (`GET_CONTENT`) or `SEND`/`VIEW` intents declared in the manifest. Do not add broader storage permissions to work around a file path issue — fix the SAF call instead.
- Keystores (`*.jks`, `*.keystore`) and model artifacts (`*.onnx`, `*.onnx_data`, `*.bin`, `models/`) are gitignored — never commit them.
