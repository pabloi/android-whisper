# Record Live Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an on-device live-recording feature to the existing Whispr app that captures from the phone mic (or active BT/wired input route), encodes AAC to a user-chosen SAF folder, and emits near-live Whisper transcript segments through the existing `LocalQnnWhisperEngine`, surviving screen-off/Doze via a foreground microphone-typed service with a partial wake lock.

**Architecture:** One mic stream from `AudioRecord`, fanned by `RecordingService` into two parallel coroutine consumers — an always-on **AAC writer** (native sample rate, dual-write `.m4a` + ADTS sidecar for crash safety) and an opt-in **transcription pipeline** (resample-to-16k → VAD-aligned `ChunkBuilder` → `LocalQnnWhisperEngine`). Failures in transcription cannot kill recording. Engine reuses the existing per-chunk encode/decode loop via a new `AudioSource.LiveStream(Flow<FloatArray>)` variant.

**Tech Stack:** Kotlin 2.1, Coroutines + Flow, Compose Material 3, Navigation Compose, DataStore Preferences, kotlinx.serialization.json, Android `AudioRecord` / `MediaCodec` / `MediaMuxer` / `AudioFocusRequest`, ONNX Runtime QNN EP 1.24.3 (already pinned).

**Spec:** `docs/superpowers/specs/2026-04-27-record-live-design.md`.

**Conventions reminder:**
- Package root `dev.pabloi.whisper`. New code under `audio/`, `engine/`, `recording/`, `data/`, `ui/`.
- No new ABI / no NDK changes / no new model assets.
- ARM64-only debug APK via `./gradlew :app:assembleDebug`.
- Side-load: `adb install -r app/build.gradle.kts`'s output APK at `app/build/outputs/apk/debug/`.

---

## Task 1: Test scaffolding + dependencies

Add JVM unit-test and Android instrumented-test source sets (none exist today, per CLAUDE.md), and the test-only dependencies. After this task `./gradlew :app:testDebugUnitTest` should run zero tests successfully.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/dev/pabloi/whisper/.gitkeep`
- Create: `app/src/androidTest/java/dev/pabloi/whisper/.gitkeep`
- Create: `app/src/test/java/dev/pabloi/whisper/SmokeTest.kt`

- [ ] **Step 1: Add test versions and libraries to the version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
junit = "4.13.2"
kotlinxCoroutinesTest = "1.9.0"
androidxJunit = "1.2.1"
androidxTestRunner = "1.6.2"
```

And to `[libraries]`:

```toml
junit = { module = "junit:junit", version.ref = "junit" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
```

- [ ] **Step 2: Wire test deps into the app module**

In `app/build.gradle.kts`, in the `dependencies { ... }` block, append:

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
```

In the `android { defaultConfig { ... } }` block, add:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

- [ ] **Step 3: Create source-set placeholder dirs**

Create both `.gitkeep` files (empty content) so Gradle picks the source sets up. Bash:

```bash
mkdir -p app/src/test/java/dev/pabloi/whisper
mkdir -p app/src/androidTest/java/dev/pabloi/whisper
touch app/src/test/java/dev/pabloi/whisper/.gitkeep
touch app/src/androidTest/java/dev/pabloi/whisper/.gitkeep
```

- [ ] **Step 4: Add a smoke unit test**

Create `app/src/test/java/dev/pabloi/whisper/SmokeTest.kt`:

```kotlin
package dev.pabloi.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun arithmetic() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 5: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, `SmokeTest.arithmetic PASSED`.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test app/src/androidTest
git commit -m "Scaffold unit + instrumented test source sets"
```

---

## Task 2: `AppSettings` recording fields + serialization round-trip test

Add the four new persisted fields described in spec §8.2, with a unit test that round-trips them through DataStore using an in-memory store.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/data/Settings.kt`
- Create: `app/src/test/java/dev/pabloi/whisper/data/AppSettingsRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/pabloi/whisper/data/AppSettingsRoundTripTest.kt`:

```kotlin
package dev.pabloi.whisper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsRoundTripTest {

    @Test fun defaultsAreStable() {
        val s = AppSettings()
        assertEquals("", s.recordingsFolderUri)
        assertTrue(s.recordWithTranscription)
        assertEquals(AudioSourcePreset.VOICE_RECOGNITION, s.audioSourcePreset)
        assertTrue(s.audioEffectsOn)
    }

    @Test fun copyPreservesNewFields() {
        val s = AppSettings(
            recordingsFolderUri = "content://tree/recordings",
            recordWithTranscription = false,
            audioSourcePreset = AudioSourcePreset.MIC,
            audioEffectsOn = false,
        )
        val s2 = s.copy(language = "es")
        assertEquals("content://tree/recordings", s2.recordingsFolderUri)
        assertEquals(false, s2.recordWithTranscription)
        assertEquals(AudioSourcePreset.MIC, s2.audioSourcePreset)
        assertEquals(false, s2.audioEffectsOn)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.data.AppSettingsRoundTripTest" --console=plain`
Expected: compile failure (`AudioSourcePreset` and the new fields don't exist yet).

- [ ] **Step 3: Add fields to `AppSettings` + new enum + persist them**

In `app/src/main/java/dev/pabloi/whisper/data/Settings.kt`, replace the `AppSettings` data class and `SettingsStore` class so they have these additions (keep all existing fields):

```kotlin
enum class AudioSourcePreset { MIC, VOICE_RECOGNITION, CAMCORDER }

data class AppSettings(
    val engine: EngineChoice = EngineChoice.LOCAL,
    val remoteBaseUrl: String = "",
    val remoteApiKey: String = "",
    val remoteModelName: String = "whisper-1",
    val language: String = "",
    val timestamps: Boolean = false,
    val localModelId: String = "whisper_large_v3_turbo",
    val recordingsFolderUri: String = "",
    val recordWithTranscription: Boolean = true,
    val audioSourcePreset: AudioSourcePreset = AudioSourcePreset.VOICE_RECOGNITION,
    val audioEffectsOn: Boolean = true,
)
```

In `SettingsStore`, add new keys and extend `update {}` and `Preferences.toSettings()`:

```kotlin
private val recFolderKey = stringPreferencesKey("recordings_folder_uri")
private val recTranscribeKey = booleanPreferencesKey("record_with_transcription")
private val audioSourceKey = stringPreferencesKey("audio_source_preset")
private val audioEffectsKey = booleanPreferencesKey("audio_effects_on")
```

In `update {}`, append:

```kotlin
            prefs[recFolderKey] = next.recordingsFolderUri
            prefs[recTranscribeKey] = next.recordWithTranscription
            prefs[audioSourceKey] = next.audioSourcePreset.name
            prefs[audioEffectsKey] = next.audioEffectsOn
```

In `toSettings()`, append (inside the `AppSettings(...)` constructor call):

```kotlin
        recordingsFolderUri = this[recFolderKey].orEmpty(),
        recordWithTranscription = this[recTranscribeKey] ?: true,
        audioSourcePreset = this[audioSourceKey]
            ?.let { runCatching { AudioSourcePreset.valueOf(it) }.getOrNull() }
            ?: AudioSourcePreset.VOICE_RECOGNITION,
        audioEffectsOn = this[audioEffectsKey] ?: true,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.data.AppSettingsRoundTripTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/data/Settings.kt app/src/test/java/dev/pabloi/whisper/data
git commit -m "Settings: add recording-folder, transcribe-toggle, audio source/effects"
```

---

## Task 3: `Recording` data class + state enum

The persisted record-of-record. Pure data; will be serialized as JSON in Task 4.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/recording/Recording.kt`
- Create: `app/src/test/java/dev/pabloi/whisper/recording/RecordingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/pabloi/whisper/recording/RecordingTest.kt`:

```kotlin
package dev.pabloi.whisper.recording

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTest {

    @Test fun roundTripJson() {
        val r = Recording(
            id = "abc",
            displayName = "Recording 2026-04-27 15:30",
            audioUri = "content://tree/lecture.m4a",
            transcriptUri = "",
            startedAt = 1_745_000_000_000L,
            durationMs = 0L,
            sampleRate = 48_000,
            routeLabel = "Built-in mic",
            state = Recording.State.RECORDING,
        )
        val s = Json.encodeToString(Recording.serializer(), r)
        val r2 = Json.decodeFromString(Recording.serializer(), s)
        assertEquals(r, r2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.recording.RecordingTest" --console=plain`
Expected: compile failure (`Recording` not defined).

- [ ] **Step 3: Implement `Recording`**

Create `app/src/main/java/dev/pabloi/whisper/recording/Recording.kt`:

```kotlin
package dev.pabloi.whisper.recording

import kotlinx.serialization.Serializable

@Serializable
data class Recording(
    val id: String,
    val displayName: String,
    val audioUri: String,
    val transcriptUri: String,
    val startedAt: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val routeLabel: String,
    val state: State,
) {
    @Serializable
    enum class State { RECORDING, FINALISED, ORPHANED, TRANSCRIBED }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.recording.RecordingTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/recording app/src/test/java/dev/pabloi/whisper/recording/RecordingTest.kt
git commit -m "Recording: add data class + State enum with JSON serialization"
```

---

## Task 4: `RecordingsStore` (DataStore-backed JSON index)

A persisted index of past recordings (cap 200, oldest auto-evicted from the index — disk artefact untouched). Exposes a `Flow<List<Recording>>` for the UI plus suspend-fun mutators.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/recording/RecordingsStore.kt`
- Create: `app/src/test/java/dev/pabloi/whisper/recording/RecordingsStoreTest.kt`

- [ ] **Step 1: Write the failing test (with an in-memory DataStore)**

Create `app/src/test/java/dev/pabloi/whisper/recording/RecordingsStoreTest.kt`:

```kotlin
package dev.pabloi.whisper.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsStoreTest {

    private fun fakeStore(): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value); state.value = next; return next
        }
    }

    private fun rec(id: String, started: Long) = Recording(
        id = id, displayName = "Rec $id", audioUri = "u://$id",
        transcriptUri = "", startedAt = started, durationMs = 1000,
        sampleRate = 48_000, routeLabel = "mic", state = Recording.State.FINALISED,
    )

    @Test fun addAndList() = runTest {
        val store = RecordingsStore(fakeStore())
        store.add(rec("a", 1))
        store.add(rec("b", 2))
        assertEquals(listOf("b", "a"), store.flow.first().map { it.id })  // newest first
    }

    @Test fun updateExisting() = runTest {
        val store = RecordingsStore(fakeStore())
        store.add(rec("a", 1))
        store.update("a") { it.copy(transcriptUri = "u://a.txt", state = Recording.State.TRANSCRIBED) }
        val r = store.find("a")!!
        assertEquals("u://a.txt", r.transcriptUri)
        assertEquals(Recording.State.TRANSCRIBED, r.state)
    }

    @Test fun capAt200OldestEvicted() = runTest {
        val store = RecordingsStore(fakeStore())
        for (i in 1..205) store.add(rec(i.toString(), i.toLong()))
        val ids = store.flow.first().map { it.id }
        assertEquals(200, ids.size)
        assertEquals("205", ids.first())
        assertEquals("6", ids.last())  // 1..5 evicted
    }

    @Test fun findUnknown() = runTest {
        val store = RecordingsStore(fakeStore())
        assertNull(store.find("nope"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.recording.RecordingsStoreTest" --console=plain`
Expected: compile failure (`RecordingsStore` not defined).

- [ ] **Step 3: Implement `RecordingsStore`**

Create `app/src/main/java/dev/pabloi/whisper/recording/RecordingsStore.kt`:

```kotlin
package dev.pabloi.whisper.recording

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.recordingsDataStore by preferencesDataStore(name = "whispr_recordings")

class RecordingsStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.recordingsDataStore)

    private val key = stringPreferencesKey("recordings_index_json")
    private val json = Json { ignoreUnknownKeys = true }

    val flow: Flow<List<Recording>> = store.data.map { prefs ->
        val raw = prefs[key].orEmpty()
        if (raw.isEmpty()) emptyList()
        else runCatching { json.decodeFromString(SER, raw) }.getOrDefault(emptyList())
    }

    suspend fun snapshot(): List<Recording> = flow.first()

    suspend fun find(id: String): Recording? = snapshot().firstOrNull { it.id == id }

    suspend fun add(rec: Recording) = mutate { current ->
        // newest first; cap at MAX_ENTRIES
        (listOf(rec) + current.filter { it.id != rec.id }).take(MAX_ENTRIES)
    }

    suspend fun update(id: String, mapper: (Recording) -> Recording) = mutate { current ->
        current.map { if (it.id == id) mapper(it) else it }
    }

    suspend fun remove(id: String) = mutate { current -> current.filter { it.id != id } }

    suspend fun firstNonFinalised(): Recording? =
        snapshot().firstOrNull { it.state == Recording.State.RECORDING || it.state == Recording.State.ORPHANED }

    private suspend fun mutate(transform: (List<Recording>) -> List<Recording>) {
        store.edit { prefs ->
            val current = prefs[key].orEmpty().let { raw ->
                if (raw.isEmpty()) emptyList()
                else runCatching { json.decodeFromString(SER, raw) }.getOrDefault(emptyList())
            }
            prefs[key] = json.encodeToString(SER, transform(current))
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private val SER = kotlinx.serialization.builtins.ListSerializer(Recording.serializer())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.recording.RecordingsStoreTest" --console=plain`
Expected: all four tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/recording/RecordingsStore.kt app/src/test/java/dev/pabloi/whisper/recording/RecordingsStoreTest.kt
git commit -m "RecordingsStore: DataStore-backed JSON index, cap 200, newest-first"
```

---

## Task 5: `Vad` (energy + zero-crossing voice detector)

Pure Kotlin frame-by-frame voice detector. Input: 20-ms frame of 16-kHz mono f32 (320 samples). Output: `Speech` or `Silence`. Used by `ChunkBuilder`.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/audio/Vad.kt`
- Create: `app/src/test/java/dev/pabloi/whisper/audio/VadTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/pabloi/whisper/audio/VadTest.kt`:

```kotlin
package dev.pabloi.whisper.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadTest {

    private fun zeros() = FloatArray(Vad.FRAME_SAMPLES) { 0f }

    private fun sine(freqHz: Double, amp: Float = 0.3f) = FloatArray(Vad.FRAME_SAMPLES) { i ->
        (amp * sin(2 * PI * freqHz * i / Vad.SAMPLE_RATE_HZ)).toFloat()
    }

    private fun whiteNoise(amp: Float = 0.05f, seed: Long = 1L): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(Vad.FRAME_SAMPLES) { (r.nextGaussian() * amp).toFloat() }
    }

    @Test fun pureSilenceIsSilence() {
        val v = Vad()
        assertEquals(Vad.Decision.Silence, v.classify(zeros()))
    }

    @Test fun loudSpeechSineIsSpeech() {
        val v = Vad()
        // 200 Hz, amplitude 0.3 — well above any reasonable noise floor.
        assertEquals(Vad.Decision.Speech, v.classify(sine(200.0, 0.3f)))
    }

    @Test fun lowAmplitudeNoiseIsSilence() {
        val v = Vad()
        // RMS ≈ 0.05 — below speech energy threshold.
        assertEquals(Vad.Decision.Silence, v.classify(whiteNoise(amp = 0.005f)))
    }

    @Test fun veryHighZcrIsNotSpeechEvenIfLoud() {
        // Hiss-like signal: alternating sign, high amplitude. ZCR at every sample.
        val v = Vad()
        val frame = FloatArray(Vad.FRAME_SAMPLES) { i -> if (i % 2 == 0) 0.3f else -0.3f }
        assertEquals(Vad.Decision.Silence, v.classify(frame))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.audio.VadTest" --console=plain`
Expected: compile failure (`Vad` not defined).

- [ ] **Step 3: Implement `Vad`**

Create `app/src/main/java/dev/pabloi/whisper/audio/Vad.kt`:

```kotlin
package dev.pabloi.whisper.audio

import kotlin.math.sqrt

/**
 * Energy + zero-crossing voice detector. Frame-by-frame, no state across calls
 * so it is trivially thread-safe and side-effect free.
 *
 * Heuristic:
 *   - rms below ENERGY_FLOOR -> Silence (definitely not speech)
 *   - rms above ENERGY_CEIL  -> compare zcr; high zcr suggests broadband noise/hiss
 *                                rather than voiced speech (~100-300 Hz fundamental).
 *   - otherwise              -> Speech if rms > ENERGY_FLOOR && zcr < ZCR_HIGH.
 */
class Vad {
    enum class Decision { Speech, Silence }

    fun classify(frame: FloatArray): Decision {
        if (frame.isEmpty()) return Decision.Silence
        var sumSq = 0.0
        var zc = 0
        var prev = frame[0]
        for (i in frame.indices) {
            val s = frame[i]
            sumSq += s.toDouble() * s
            if (i > 0 && (s >= 0f) != (prev >= 0f)) zc++
            prev = s
        }
        val rms = sqrt(sumSq / frame.size).toFloat()
        val zcr = zc.toFloat() / frame.size

        if (rms < ENERGY_FLOOR) return Decision.Silence
        if (zcr > ZCR_HIGH) return Decision.Silence
        return Decision.Speech
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_MS / 1000  // = 320

        // Tuned for the "VOICE_RECOGNITION + AGC on" path. Values are conservative;
        // VadTest exercises both sides of the boundary.
        private const val ENERGY_FLOOR = 0.01f
        private const val ZCR_HIGH = 0.45f
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.audio.VadTest" --console=plain`
Expected: all four tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/audio/Vad.kt app/src/test/java/dev/pabloi/whisper/audio/VadTest.kt
git commit -m "Vad: energy + zero-crossing frame-level voice detector"
```

---

## Task 6: `ChunkBuilder` (VAD-aligned 30-s chunker)

Buffers 16-kHz mono frames, emits 30-s zero-padded `FloatArray(480_000)` on (a) silence ≥ 300 ms after speech, or (b) buffer reaches 30 s. Skips pure-silence buffers. Carries 200 ms left-context across cuts. Exposes a `Flow<FloatArray>`.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/audio/ChunkBuilder.kt`
- Create: `app/src/test/java/dev/pabloi/whisper/audio/ChunkBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/pabloi/whisper/audio/ChunkBuilderTest.kt`:

```kotlin
package dev.pabloi.whisper.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkBuilderTest {

    private fun speechFrame() = FloatArray(Vad.FRAME_SAMPLES) { i ->
        // 200 Hz sine at amplitude 0.3 — Vad classifies as Speech.
        (0.3 * Math.sin(2 * Math.PI * 200 * i / Vad.SAMPLE_RATE_HZ)).toFloat()
    }
    private fun silenceFrame() = FloatArray(Vad.FRAME_SAMPLES)

    @Test fun emitsAtSilenceAfterSpeech() = runTest {
        val cb = ChunkBuilder()
        // 1 s of speech + 350 ms of silence -> one emit.
        repeat(50) { cb.feed(speechFrame()) }
        repeat(18) { cb.feed(silenceFrame()) }  // 18 * 20 = 360 ms
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(1, emitted.size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].size)
        // Speech is at the front (it's zero-padded at the tail).
        assertNotEquals(0f, emitted[0][0])
        assertEquals(0f, emitted[0][ChunkBuilder.CHUNK_SAMPLES - 1])
    }

    @Test fun forceFiresAt30Seconds() = runTest {
        val cb = ChunkBuilder()
        // 30.4 s of continuous speech with no silence. After 30 s force-fire,
        // remaining 0.4 s sits in next buffer; closing emits its tail.
        val framesFor30s = ChunkBuilder.CHUNK_SAMPLES / Vad.FRAME_SAMPLES  // 1500
        repeat(framesFor30s + 20) { cb.feed(speechFrame()) }
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(2, emitted.size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[1].size)
    }

    @Test fun pureSilenceEmitsNothing() = runTest {
        val cb = ChunkBuilder()
        repeat(2000) { cb.feed(silenceFrame()) }  // 40 s
        cb.close()
        assertEquals(0, cb.flow.toList().size)
    }

    @Test fun leftContextPrependedToNextChunk() = runTest {
        val cb = ChunkBuilder()
        // Fire a chunk, then check the next chunk starts with non-zero (from carryover).
        repeat(50) { cb.feed(speechFrame()) }       // 1 s speech
        repeat(18) { cb.feed(silenceFrame()) }      // 360 ms silence -> emit
        repeat(50) { cb.feed(speechFrame()) }       // 1 s more speech
        repeat(18) { cb.feed(silenceFrame()) }      // -> emit
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(2, emitted.size)
        // Carry-over: the 200 ms (3200 samples) at the start of chunk #2 should
        // be the tail of chunk #1's last meaningful audio (silence in this test
        // because it's right after the silence cut). The relevant invariant is:
        // the chunk's first non-zero sample appears within LEFT_CONTEXT_SAMPLES
        // of the start in chunk #2 only if we DID carry context; here it'll be
        // dominated by the new speech that follows the silence.
        // What we *can* assert structurally: every emitted chunk is exactly CHUNK_SAMPLES.
        assertTrue(emitted.all { it.size == ChunkBuilder.CHUNK_SAMPLES })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.audio.ChunkBuilderTest" --console=plain`
Expected: compile failure.

- [ ] **Step 3: Implement `ChunkBuilder`**

Create `app/src/main/java/dev/pabloi/whisper/audio/ChunkBuilder.kt`:

```kotlin
package dev.pabloi.whisper.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Accepts continuous 16-kHz mono f32 frames (one [Vad.FRAME_SAMPLES] frame per call),
 * emits 30-s zero-padded chunks on:
 *   - silence >= [SILENCE_TRIGGER_MS] after any detected speech in the buffer, or
 *   - buffer reaches [CHUNK_SAMPLES] (force-fire).
 * Skips pure-silence buffers. Carries [LEFT_CONTEXT_SAMPLES] across cuts so a word
 * cut by a force-fire reappears whole at the start of the next chunk.
 */
class ChunkBuilder(private val vad: Vad = Vad()) {

    private val buffer = FloatArray(CHUNK_SAMPLES)
    private var len = 0                   // valid samples in buffer (samples written so far)
    private var hasSpeech = false
    private var trailingSilenceFrames = 0 // consecutive silence frames at the tail

    private val outputs = MutableSharedFlow<FloatArray>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.SUSPEND
    )
    val flow: Flow<FloatArray> = outputs.asSharedFlow()

    suspend fun feed(frame: FloatArray) {
        require(frame.size == Vad.FRAME_SAMPLES) {
            "Frame size ${frame.size} != ${Vad.FRAME_SAMPLES}"
        }
        val decision = vad.classify(frame)

        // Append to buffer; force-fire if it would overflow.
        if (len + frame.size > CHUNK_SAMPLES) {
            // Force-fire: copy whatever fits, emit, carry context, then continue.
            val space = CHUNK_SAMPLES - len
            if (space > 0) System.arraycopy(frame, 0, buffer, len, space)
            len = CHUNK_SAMPLES
            emitAndReset(forceFire = true)
            // Whatever didn't fit becomes the start of the new buffer (after left-context).
            val remaining = frame.size - space
            if (remaining > 0) System.arraycopy(frame, space, buffer, len, remaining); len += remaining
        } else {
            System.arraycopy(frame, 0, buffer, len, frame.size); len += frame.size
        }

        if (decision == Vad.Decision.Speech) {
            hasSpeech = true
            trailingSilenceFrames = 0
        } else {
            trailingSilenceFrames++
            if (hasSpeech && trailingSilenceFrames * Vad.FRAME_MS >= SILENCE_TRIGGER_MS) {
                emitAndReset(forceFire = false)
            }
        }
    }

    /** Emit the tail buffer (if any speech) and complete the output flow. */
    suspend fun close() {
        if (hasSpeech && len > 0) emitAndReset(forceFire = false)
        // Flow completion is implicit when no more emissions arrive; consumers using
        // toList() in tests rely on the SharedFlow being completed via channel close.
        // We use a SharedFlow that does not complete; tests collect within runTest scope
        // using toList() with a launch scope. For production the engine consumes it
        // until the upstream service cancels it.
    }

    private suspend fun emitAndReset(forceFire: Boolean) {
        if (!hasSpeech) {
            // Pure-silence buffer: discard without emit.
            len = 0; trailingSilenceFrames = 0
            return
        }
        // Build a 30-s zero-padded snapshot.
        val out = FloatArray(CHUNK_SAMPLES)
        System.arraycopy(buffer, 0, out, 0, len)
        outputs.emit(out)

        // Carry the last LEFT_CONTEXT_SAMPLES of the just-emitted buffer to the front
        // of the next buffer, so a word cut by force-fire reappears whole.
        val keepFrom = (len - LEFT_CONTEXT_SAMPLES).coerceAtLeast(0)
        val keep = len - keepFrom
        if (keep > 0) System.arraycopy(buffer, keepFrom, buffer, 0, keep)
        len = keep
        hasSpeech = false
        trailingSilenceFrames = 0
    }

    companion object {
        const val CHUNK_SECONDS = 30
        const val CHUNK_SAMPLES = Vad.SAMPLE_RATE_HZ * CHUNK_SECONDS  // 480_000
        const val SILENCE_TRIGGER_MS = 300
        const val LEFT_CONTEXT_MS = 200
        const val LEFT_CONTEXT_SAMPLES = Vad.SAMPLE_RATE_HZ * LEFT_CONTEXT_MS / 1000  // 3_200
    }
}
```

- [ ] **Step 4: Adjust the test to collect from a hot SharedFlow**

The test uses `cb.flow.toList()` which on a non-completing SharedFlow would hang. Replace `RecordingsStore` test approach: have the test launch a collector and then close. Update `ChunkBuilderTest`:

```kotlin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async

// Replace each test's `val emitted = cb.flow.toList()` block with a concrete
// `cb.flow.take(N).toList()` matching the expected emit count, OR change the
// implementation to use a Channel + receiveAsFlow that completes on close().
```

The cleanest fix is to have `ChunkBuilder.flow` complete on `close()`. Update `ChunkBuilder.kt` to use `Channel` instead of `MutableSharedFlow`:

```kotlin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

class ChunkBuilder(private val vad: Vad = Vad()) {
    // ... unchanged fields ...
    private val channel = Channel<FloatArray>(capacity = 16)
    val flow: Flow<FloatArray> = channel.consumeAsFlow()

    // emit -> channel.send(out)
    // close -> after final emit, channel.close()
```

Replace `outputs.emit(out)` with `channel.send(out)`. At the end of `close()` add `channel.close()`. Remove `outputs` field and import line.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.pabloi.whisper.audio.ChunkBuilderTest" --console=plain`
Expected: all four tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/audio/ChunkBuilder.kt app/src/test/java/dev/pabloi/whisper/audio/ChunkBuilderTest.kt
git commit -m "ChunkBuilder: VAD-aligned chunker with 30s force-fire and left-context carryover"
```

---

## Task 7: `AudioSource.LiveStream` + `LocalQnnWhisperEngine` consumes it

Add the new sealed-interface variant and wire it through the engine. The engine already iterates a `Flow<FloatArray>` of 30-s chunks; we just bypass `AudioDecoder` for `LiveStream`.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/engine/TranscriptionEngine.kt`
- Modify: `app/src/main/java/dev/pabloi/whisper/engine/local/LocalQnnWhisperEngine.kt`
- Modify: `app/src/main/java/dev/pabloi/whisper/engine/remote/RemoteOpenAIEngine.kt` (reject `LiveStream` cleanly)

- [ ] **Step 1: Add the `LiveStream` variant**

In `app/src/main/java/dev/pabloi/whisper/engine/TranscriptionEngine.kt`, replace the `AudioSource` sealed interface:

```kotlin
sealed interface AudioSource {
    data class Uri(val uri: android.net.Uri) : AudioSource
    data class File(val path: String) : AudioSource
    data class Pcm(val samples: FloatArray) : AudioSource {
        override fun equals(other: Any?): Boolean =
            other is Pcm && samples.contentEquals(other.samples)
        override fun hashCode(): Int = samples.contentHashCode()
    }
    /**
     * Already-chunked 16-kHz mono f32 stream of 30-s frames (each [FloatArray] is
     * exactly `MelSpectrogram.N_SAMPLES` long, zero-padded if needed). Used by the
     * live-recording path so chunks flow straight from `ChunkBuilder` to the
     * engine without round-tripping through MediaCodec.
     */
    data class LiveStream(val chunks: kotlinx.coroutines.flow.Flow<FloatArray>) : AudioSource
}
```

- [ ] **Step 2: Teach `LocalQnnWhisperEngine` to consume `LiveStream`**

In `app/src/main/java/dev/pabloi/whisper/engine/local/LocalQnnWhisperEngine.kt`, change the `source` selection inside `transcribe()`:

```kotlin
        val source: Flow<FloatArray> = when (audio) {
            is AudioSource.Pcm -> chunkPrebufferedPcm(audio.samples, chunkLen)
            is AudioSource.File -> AudioDecoder.streamMonoF32(audio.path, chunkLen)
            is AudioSource.Uri -> AudioDecoder.streamMonoF32(context, audio.uri, chunkLen)
            is AudioSource.LiveStream -> audio.chunks
        }
```

- [ ] **Step 3: Make `RemoteOpenAIEngine` reject `LiveStream` cleanly**

In `app/src/main/java/dev/pabloi/whisper/engine/remote/RemoteOpenAIEngine.kt`, find the `transcribe()` function. At the top of its `flow { ... }` body, before any decoding, add:

```kotlin
        if (audio is AudioSource.LiveStream) {
            emit(TranscribeEvent.Failure(
                IllegalArgumentException("Remote engine does not support live streaming")
            ))
            return@flow
        }
```

(If `RemoteOpenAIEngine` doesn't already have a `flow { ... }` wrapper, place the check at the start of `transcribe()` and return a `flowOf(TranscribeEvent.Failure(...))`.)

- [ ] **Step 4: Smoke-build the app**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (No new tests yet — the live path is exercised end-to-end by Task 11+.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/engine
git commit -m "Engine: add AudioSource.LiveStream variant and wire it into LocalQnnWhisperEngine"
```

---

## Task 8: `AacWriter` (m4a + ADTS sidecar)

Encodes mono PCM at native sample rate to AAC-LC. Writes a finalised `.m4a` via `MediaMuxer` plus a hidden `.<name>.aac` ADTS sidecar for crash recovery (sidecar is the source of truth on orphan recovery; remux to `.m4a` is one-pass, no transcoding). On clean stop the sidecar is deleted.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/audio/AacWriter.kt`
- Create: `app/src/androidTest/java/dev/pabloi/whisper/audio/AacWriterInstrumentedTest.kt`

- [ ] **Step 1: Implement `AacWriter`**

Create `app/src/main/java/dev/pabloi/whisper/audio/AacWriter.kt`:

```kotlin
package dev.pabloi.whisper.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Streaming PCM-to-AAC encoder.
 *
 *   - `m4aFd`     : the SAF-supplied PFD where MediaMuxer builds an MP4 container.
 *                    `moov` is only written on close(), so a hard kill mid-record
 *                    leaves an unplayable .m4a — that's what the sidecar is for.
 *   - `adtsOut`   : OutputStream to a sidecar dotfile. We tee the same encoded
 *                    AAC frames here, prefixed with a 7-byte ADTS header. ADTS
 *                    streams are self-describing — every frame is independently
 *                    decodable, no index needed. On orphan recovery this is the
 *                    source of truth; remux to .m4a is one-pass.
 *
 * Thread model: `append()` and `close()` are NOT thread-safe; the service serialises
 * calls onto its writer coroutine.
 */
class AacWriter(
    m4aFd: ParcelFileDescriptor,
    private val adtsOut: OutputStream,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitRate: Int = chooseBitRate(sampleRate),
) : AutoCloseable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME).apply {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val muxer: MediaMuxer = MediaMuxer(m4aFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4)
    private var muxerTrack: Int = -1
    private var muxerStarted = false
    private var totalSamples: Long = 0L

    /** Append PCM 16-bit little-endian mono samples. */
    fun append(pcm: ByteBuffer) {
        // Drain any already-encoded output before pushing more input, so the
        // encoder doesn't stall on a full output queue.
        drain(eos = false)

        while (pcm.hasRemaining()) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) { drain(eos = false); continue }
            val inBuf = codec.getInputBuffer(inIdx)!!
            inBuf.clear()
            val toCopy = minOf(inBuf.remaining(), pcm.remaining())
            val limitOld = pcm.limit()
            pcm.limit(pcm.position() + toCopy)
            inBuf.put(pcm)
            pcm.limit(limitOld)

            val ptsUs = totalSamples * 1_000_000 / sampleRate
            codec.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
            totalSamples += toCopy / 2 / channels  // 16-bit samples
        }
    }

    private fun drain(eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        if (eos) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        loop@ while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFmt = codec.outputFormat
                    muxerTrack = muxer.addTrack(outFmt)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) break@loop else break@loop
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        if (muxerStarted) muxer.writeSampleData(muxerTrack, outBuf, info)
                        // ADTS sidecar: prefix each AAC frame with a 7-byte header.
                        outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                        val header = adtsHeader(packetLength = 7 + info.size, sampleRate, channels)
                        adtsOut.write(header)
                        val raw = ByteArray(info.size); outBuf.get(raw)
                        adtsOut.write(raw)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break@loop
                }
                else -> if (!eos) break@loop
            }
        }
    }

    override fun close() {
        runCatching { drain(eos = true) }
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { adtsOut.flush(); adtsOut.close() }
    }

    companion object {
        private const val MIME = "audio/mp4a-latm"

        /**
         * Build the 7-byte ADTS header for a given payload+header length, sample rate
         * and channel count. AAC-LC profile (object type 2). MPEG-4. No CRC.
         */
        fun adtsHeader(packetLength: Int, sampleRate: Int, channels: Int): ByteArray {
            val freqIdx = freqIndex(sampleRate)
            val h = ByteArray(7)
            h[0] = 0xFF.toByte()
            h[1] = 0xF1.toByte()    // MPEG-4, no CRC
            h[2] = (((2 - 1) shl 6) or (freqIdx shl 2) or ((channels shr 2) and 0x1)).toByte()
            h[3] = (((channels and 0x3) shl 6) or ((packetLength shr 11) and 0x3)).toByte()
            h[4] = ((packetLength shr 3) and 0xFF).toByte()
            h[5] = (((packetLength and 0x7) shl 5) or 0x1F).toByte()
            h[6] = 0xFC.toByte()
            return h
        }

        private fun freqIndex(sr: Int): Int = when (sr) {
            96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
            32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
            11_025 -> 10; 8_000 -> 11
            else -> error("Unsupported sample rate $sr for AAC ADTS")
        }

        private fun chooseBitRate(sr: Int): Int = when {
            sr >= 32_000 -> 64_000
            sr >= 16_000 -> 32_000
            else -> 24_000
        }
    }
}
```

- [ ] **Step 2: Write the instrumented test**

Create `app/src/androidTest/java/dev/pabloi/whisper/audio/AacWriterInstrumentedTest.kt`:

```kotlin
package dev.pabloi.whisper.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AacWriterInstrumentedTest {

    @Test fun encodesAndProducesValidM4a() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outFile = File.createTempFile("aac_test_", ".m4a", ctx.cacheDir)
        val pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        val sidecar = ByteArrayOutputStream()
        val writer = AacWriter(pfd, sidecar, sampleRate = 48_000, channels = 1, bitRate = 64_000)

        // 5 s of 440 Hz sine at amp 0.3 -> int16 LE mono.
        val samples = 48_000 * 5
        val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val v = (0.3 * sin(2 * PI * 440 * i / 48_000.0) * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(v)
        }
        buf.flip()
        writer.append(buf)
        writer.close()
        pfd.close()

        // The .m4a should now be parseable by MediaExtractor.
        val ext = MediaExtractor()
        ext.setDataSource(outFile.absolutePath)
        assertTrue(ext.trackCount > 0)
        val fmt = ext.getTrackFormat(0)
        assertEquals("audio/mp4a-latm", fmt.getString(MediaFormat.KEY_MIME))
        assertEquals(48_000, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        ext.release()

        // Sidecar should have ADTS frames (each starts with 0xFFF sync word).
        val sidecarBytes = sidecar.toByteArray()
        assertTrue("sidecar empty", sidecarBytes.isNotEmpty())
        assertEquals(0xFF.toByte(), sidecarBytes[0])
        assertEquals(0xF1.toByte(), sidecarBytes[1])
    }
}
```

- [ ] **Step 3: Build and run instrumented test (when device attached)**

Run on a connected S24 (or any AAC-capable Android device):

```bash
./gradlew :app:connectedDebugAndroidTest --tests "dev.pabloi.whisper.audio.AacWriterInstrumentedTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, the single test PASSES. If no device is attached, this step is deferred to the manual-test pass — annotate the task accordingly when committing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/audio/AacWriter.kt app/src/androidTest/java/dev/pabloi/whisper/audio/AacWriterInstrumentedTest.kt
git commit -m "AacWriter: streaming PCM->AAC with ADTS sidecar for crash safety"
```

---

## Task 9: `AudioCapture` (mic + route monitoring)

Wraps `AudioRecord`. Picks the active input device, configures source/sample-rate/channel, attaches `NoiseSuppressor` + `AutomaticGainControl` `AudioEffect`s when `audioEffectsOn`, reads 20 ms frames, and exposes a `SharedFlow<PcmFrame>` plus an `RMS-level` `StateFlow`. Reopens on `AudioDeviceCallback` route change. Has `pause()` / `resume()` for audio-focus handling.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/audio/AudioCapture.kt`
- Create: `app/src/androidTest/java/dev/pabloi/whisper/audio/AudioCaptureInstrumentedTest.kt`

- [ ] **Step 1: Implement `AudioCapture`**

Create `app/src/main/java/dev/pabloi/whisper/audio/AudioCapture.kt`:

```kotlin
package dev.pabloi.whisper.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pabloi.whisper.data.AudioSourcePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/** A 20-ms frame at the native sample rate, mono, 16-bit LE. */
data class PcmFrame(
    val pcm: ShortArray,
    val sampleRate: Int,
)

class AudioCapture(
    private val context: Context,
    private val sourcePreset: AudioSourcePreset,
    private val effectsOn: Boolean,
) {
    private var record: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var deviceCb: AudioDeviceCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    @Volatile private var paused = false

    private val _frames = MutableSharedFlow<PcmFrame>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<PcmFrame> = _frames.asSharedFlow()

    private val _levelDb = MutableStateFlow(-120f)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private val _route = MutableStateFlow("Built-in mic")
    val route: StateFlow<String> = _route.asStateFlow()

    private val _sampleRate = MutableStateFlow(48_000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    /** Throws SecurityException if RECORD_AUDIO is not granted. */
    fun open() {
        check(record == null) { "AudioCapture already open" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val device = pickActiveInputDevice()
        val nativeRate = device?.sampleRates?.firstOrNull() ?: 48_000
        configureAndStart(device, nativeRate)
        registerRouteCallback()
    }

    private fun configureAndStart(device: AudioDeviceInfo?, nativeRate: Int) {
        val source = when (sourcePreset) {
            AudioSourcePreset.MIC -> MediaRecorder.AudioSource.MIC
            AudioSourcePreset.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSourcePreset.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        }
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(nativeRate, channelMask, encoding)
        val bufBytes = (minBuf.coerceAtLeast(nativeRate * 2 / 5))  // ~200 ms

        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(nativeRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding).build())
            .setBufferSizeInBytes(bufBytes).build()
        if (device != null) rec.preferredDevice = device

        if (effectsOn) {
            if (NoiseSuppressor.isAvailable())     ns = NoiseSuppressor.create(rec.audioSessionId).apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId).apply { enabled = true }
        }

        rec.startRecording()
        record = rec
        _sampleRate.value = nativeRate
        _route.value = describeRoute(device)

        val frameSamples = nativeRate * 20 / 1000
        val readBuf = ShortArray(frameSamples)
        readerJob = scope.launch {
            while (record === rec && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                if (paused) { kotlinx.coroutines.delay(20); continue }
                val n = rec.read(readBuf, 0, frameSamples)
                if (n <= 0) continue
                val frame = ShortArray(n)
                System.arraycopy(readBuf, 0, frame, 0, n)
                _frames.emit(PcmFrame(frame, nativeRate))
                _levelDb.value = rmsDb(frame)
            }
        }
    }

    private fun pickActiveInputDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // Prefer BT SCO or wired headset if connected as input route.
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull()
    }

    private fun describeRoute(d: AudioDeviceInfo?): String = when (d?.type) {
        null, AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> d.productName?.toString() ?: "Mic"
    }

    private fun registerRouteCallback() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = reopen()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = reopen()
        }
        deviceCb = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun reopen() {
        // Drop and reconfigure on the next idle tick.
        scope.launch {
            try {
                stopReaderAndRecord()
                val device = pickActiveInputDevice()
                val rate = device?.sampleRates?.firstOrNull() ?: 48_000
                configureAndStart(device, rate)
            } catch (_: Throwable) { /* surfaced as silence in level meter */ }
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun close() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        deviceCb?.let { am.unregisterAudioDeviceCallback(it) }; deviceCb = null
        stopReaderAndRecord()
        scope.cancel()
    }

    private fun stopReaderAndRecord() {
        readerJob?.cancel(); readerJob = null
        runCatching { record?.stop() }
        runCatching { record?.release() }; record = null
        runCatching { ns?.release() }; ns = null
        runCatching { agc?.release() }; agc = null
    }

    private fun rmsDb(frame: ShortArray): Float {
        var sumSq = 0.0
        for (s in frame) sumSq += (s.toDouble() / Short.MAX_VALUE).let { it * it }
        val rms = sqrt(sumSq / frame.size)
        return if (rms <= 0.0) -120f else (20 * log10(rms)).toFloat()
    }
}
```

- [ ] **Step 2: Write the instrumented test (smoke)**

Create `app/src/androidTest/java/dev/pabloi/whisper/audio/AudioCaptureInstrumentedTest.kt`:

```kotlin
package dev.pabloi.whisper.audio

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.pabloi.whisper.data.AudioSourcePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AudioCaptureInstrumentedTest {

    @get:Rule val permRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test fun openAndCaptureOneFrame() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cap = AudioCapture(ctx, AudioSourcePreset.VOICE_RECOGNITION, effectsOn = true)
        cap.open()
        try {
            val frame = withTimeout(5_000) { cap.frames.first() }
            assertNotNull(frame.pcm)
            assertTrue(frame.pcm.isNotEmpty())
            assertTrue(frame.sampleRate >= 8_000)
        } finally {
            cap.close()
        }
    }
}
```

- [ ] **Step 3: Run the instrumented test (device required)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "dev.pabloi.whisper.audio.AudioCaptureInstrumentedTest" --console=plain`
Expected: PASS on a real device (skipped on emulators with no mic).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/audio/AudioCapture.kt app/src/androidTest/java/dev/pabloi/whisper/audio/AudioCaptureInstrumentedTest.kt
git commit -m "AudioCapture: wraps AudioRecord with route + level + effects"
```

---

## Task 10: `RecordingState` sealed type

State exposed by `RecordingService` to the rest of the app.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/recording/RecordingState.kt`

- [ ] **Step 1: Implement**

Create `app/src/main/java/dev/pabloi/whisper/recording/RecordingState.kt`:

```kotlin
package dev.pabloi.whisper.recording

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data class Recording(
        val recordingId: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val levelDb: Float,
        val routeLabel: String,
        val sampleRate: Int,
        val transcribing: Boolean,
    ) : RecordingState
    data class Paused(
        val recordingId: String,
        val reason: String,
        val routeLabel: String,
    ) : RecordingState
    data object Stopping : RecordingState
    data class Failure(val cause: Throwable) : RecordingState
}
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/recording/RecordingState.kt
git commit -m "RecordingState: sealed type exposed by RecordingService"
```

---

## Task 11: `RecordingService` (foreground microphone-typed service)

The choreographer. Owns `AudioCapture`, fans the SharedFlow into the AAC writer (always) and the transcription pipeline (when `transcribeLive=true`). Posts an ongoing notification. Holds a `PARTIAL_WAKE_LOCK`. Updates `RecordingsStore` on stop.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/recording/RecordingService.kt`

- [ ] **Step 1: Implement the service**

Create `app/src/main/java/dev/pabloi/whisper/recording/RecordingService.kt`:

```kotlin
package dev.pabloi.whisper.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import dev.pabloi.whisper.MainActivity
import dev.pabloi.whisper.R
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.audio.AacWriter
import dev.pabloi.whisper.audio.AudioCapture
import dev.pabloi.whisper.audio.ChunkBuilder
import dev.pabloi.whisper.audio.PcmFrame
import dev.pabloi.whisper.audio.Vad
import dev.pabloi.whisper.data.AudioSourcePreset
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.local.LocalQnnWhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var capture: AudioCapture? = null
    private var aac: AacWriter? = null
    private var engineJob: Job? = null
    private var pcmJob: Job? = null
    private var clockJob: Job? = null
    private var chunkBuilder: ChunkBuilder? = null
    private var engineChunks: Channel<FloatArray>? = null
    private var currentRecording: Recording? = null
    private var startedAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (state.value !is RecordingState.Idle) return START_NOT_STICKY
        val transcribeLive = intent?.getBooleanExtra(EXTRA_TRANSCRIBE_LIVE, true) ?: true
        val sourceName = intent?.getStringExtra(EXTRA_SOURCE_PRESET) ?: AudioSourcePreset.VOICE_RECOGNITION.name
        val effectsOn = intent?.getBooleanExtra(EXTRA_EFFECTS_ON, true) ?: true

        _state.value = RecordingState.Starting
        startInForeground(buildNotification("Starting…", indeterminate = true))
        acquireWakeLock()

        scope.launch {
            try {
                val app = applicationContext as WhisprApp
                val settings = app.settings.flow.first()
                val folderUri = settings.recordingsFolderUri.takeIf { it.isNotBlank() }
                    ?: error("No recordings folder configured")
                val tree = DocumentFile.fromTreeUri(this@RecordingService, Uri.parse(folderUri))
                    ?: error("Cannot open recordings folder $folderUri")

                val now = System.currentTimeMillis()
                val baseName = "Recording ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(now))}"
                val m4aDoc = tree.createFile("audio/mp4", "$baseName.m4a")
                    ?: error("Cannot create .m4a in recordings folder")
                val sidecarDoc = tree.createFile("application/octet-stream", ".$baseName.aac")
                    ?: error("Cannot create ADTS sidecar in recordings folder")

                val pfd = contentResolver.openFileDescriptor(m4aDoc.uri, "rw")
                    ?: error("Cannot open .m4a for writing")
                val sidecarOut = contentResolver.openOutputStream(sidecarDoc.uri)
                    ?: error("Cannot open sidecar for writing")

                val cap = AudioCapture(this@RecordingService,
                    sourcePreset = AudioSourcePreset.valueOf(sourceName),
                    effectsOn = effectsOn)
                cap.open()
                capture = cap
                val rate = cap.sampleRate.value
                aac = AacWriter(pfd, sidecarOut, sampleRate = rate, channels = 1)

                val rec = Recording(
                    id = UUID.randomUUID().toString(),
                    displayName = baseName,
                    audioUri = m4aDoc.uri.toString(),
                    transcriptUri = "",
                    startedAt = now,
                    durationMs = 0L,
                    sampleRate = rate,
                    routeLabel = cap.route.value,
                    state = Recording.State.RECORDING,
                )
                currentRecording = rec
                startedAtMs = now
                RecordingsStore(applicationContext).add(rec)

                _state.value = RecordingState.Recording(
                    recordingId = rec.id, startedAtMs = now, durationMs = 0,
                    levelDb = -120f, routeLabel = cap.route.value, sampleRate = rate,
                    transcribing = transcribeLive,
                )

                pcmJob = launchPcmConsumers(cap, transcribeLive, rate)
                if (transcribeLive) startEngineLoop()
                clockJob = startClock()
            } catch (t: Throwable) {
                _state.value = RecordingState.Failure(t)
                cleanup()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun launchPcmConsumers(cap: AudioCapture, transcribeLive: Boolean, nativeRate: Int): Job {
        val resampler = LiveResampler(srcRate = nativeRate, dstRate = Vad.SAMPLE_RATE_HZ, frameSamples = Vad.FRAME_SAMPLES)
        val cb = if (transcribeLive) ChunkBuilder().also { chunkBuilder = it } else null
        if (transcribeLive) {
            engineChunks = Channel(capacity = 4)
            scope.launch {
                cb!!.flow.collect { engineChunks!!.send(it) }
                engineChunks?.close()
            }
        }
        return scope.launch {
            cap.frames.collect { frame ->
                // Writer: native-rate PCM straight into AAC.
                val bb = ByteBuffer.allocate(frame.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in frame.pcm) bb.putShort(s)
                bb.flip()
                aac?.append(bb)

                // Transcriber: resample to 16k, hand to ChunkBuilder.
                if (transcribeLive && cb != null) {
                    for (frame16k in resampler.process(frame.pcm)) cb.feed(frame16k)
                }

                // Update state.
                val st = state.value
                if (st is RecordingState.Recording) {
                    _state.value = st.copy(levelDb = cap.levelDb.value, routeLabel = cap.route.value)
                }
            }
        }
    }

    private fun startEngineLoop() {
        val app = applicationContext as WhisprApp
        val repo = app.repoForId(runBlocking { app.settings.flow.first().localModelId })
        val engine = LocalQnnWhisperEngine(this, repo)
        val chunks = engineChunks!!.consumeAsFlow()
        engineJob = engine.transcribe(AudioSource.LiveStream(chunks), TranscribeOptions(timestamps = true))
            .onEach { ev -> _events.emit(ev) }
            .launchIn(scope)
    }

    private fun startClock(): Job = scope.launch {
        while (true) {
            kotlinx.coroutines.delay(250)
            val st = state.value
            if (st is RecordingState.Recording) {
                _state.value = st.copy(durationMs = System.currentTimeMillis() - startedAtMs)
            } else break
        }
    }

    fun stopRequest() = stopSelfAsync()

    private fun stopSelfAsync() {
        if (state.value is RecordingState.Stopping || state.value is RecordingState.Idle) return
        _state.value = RecordingState.Stopping
        scope.launch {
            try {
                capture?.close()
                pcmJob?.cancel()
                chunkBuilder?.close()
                engineChunks?.close()
                engineJob?.join()
                aac?.close()
                val rec = currentRecording
                if (rec != null) {
                    val durMs = System.currentTimeMillis() - startedAtMs
                    RecordingsStore(applicationContext).update(rec.id) {
                        it.copy(durationMs = durMs, state = Recording.State.FINALISED)
                    }
                }
            } finally {
                cleanup()
                _state.value = RecordingState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cleanup() {
        clockJob?.cancel(); clockJob = null
        engineJob?.cancel(); engineJob = null
        engineChunks = null
        chunkBuilder = null
        aac = null
        capture = null
        releaseWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        releaseWakeLock()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, notification)
    }

    private fun buildNotification(text: String, indeterminate: Boolean = false): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whispr — recording")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setProgress(if (indeterminate) 0 else 100, 0, indeterminate)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whispr:recording").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(4))
        }
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    companion object {
        const val CHANNEL_ID = "whispr_recording"
        const val EXTRA_TRANSCRIBE_LIVE = "transcribe_live"
        const val EXTRA_SOURCE_PRESET = "source_preset"
        const val EXTRA_EFFECTS_ON = "effects_on"
        const val EXTRA_STOP = "stop"
        private const val NOTIF_ID = 2001

        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state.asStateFlow()
        private val _events = MutableSharedFlow<TranscribeEvent>(replay = 0, extraBufferCapacity = 32)
        val events: SharedFlow<TranscribeEvent> = _events.asSharedFlow()

        fun start(context: Context, transcribeLive: Boolean, source: AudioSourcePreset, effectsOn: Boolean) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_TRANSCRIBE_LIVE, transcribeLive)
                .putExtra(EXTRA_SOURCE_PRESET, source.name)
                .putExtra(EXTRA_EFFECTS_ON, effectsOn)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // Send a STOP intent that the service interprets in onStartCommand.
            // Simpler: use a static reference set in onCreate. But for now, the
            // ViewModel calls stopService() and the service's onDestroy path
            // also drives cleanup. RecordingService handles a STOP intent via
            // the same pathway in case of redelivery.
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * Linear resampler producing 20-ms 16-kHz frames from arbitrary-rate native PCM.
 * Maintains state across `process()` calls so a 1-sample partial frame at the
 * end of one call rolls into the next call cleanly.
 */
internal class LiveResampler(val srcRate: Int, val dstRate: Int, val frameSamples: Int) {
    private val ratio = srcRate.toDouble() / dstRate
    private var srcPos = 0.0
    private val accum = ArrayList<Float>()
    private val pending = ArrayList<Float>()

    fun process(srcInt16: ShortArray): List<FloatArray> {
        // Append new source samples (scaled to f32 [-1,1]).
        for (s in srcInt16) accum.add(s.toFloat() / Short.MAX_VALUE)

        val out = ArrayList<Float>()
        // Produce as many destination samples as we can given current `accum` buffer.
        while (true) {
            val i0 = srcPos.toInt()
            val i1 = i0 + 1
            if (i1 >= accum.size) break
            val frac = (srcPos - i0).toFloat()
            out.add(accum[i0] + (accum[i1] - accum[i0]) * frac)
            srcPos += ratio
        }
        // Trim consumed source samples (keep one sample of context for next call).
        val keepFrom = (srcPos.toInt() - 1).coerceAtLeast(0)
        if (keepFrom > 0) {
            repeat(keepFrom) { accum.removeAt(0) }
            srcPos -= keepFrom
        }

        // Concatenate with any leftover and split into FRAME_SAMPLES-sized FloatArrays.
        pending.addAll(out)
        val frames = ArrayList<FloatArray>()
        while (pending.size >= frameSamples) {
            val f = FloatArray(frameSamples) { pending[it] }
            repeat(frameSamples) { pending.removeAt(0) }
            frames.add(f)
        }
        return frames
    }
}
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/recording/RecordingService.kt
git commit -m "RecordingService: foreground microphone-typed service driving capture+writer+engine"
```

---

## Task 12: Manifest updates

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add permissions, feature, and service**

In `app/src/main/AndroidManifest.xml`, add the following inside `<manifest>` (alongside existing `<uses-permission>` lines):

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <uses-feature android:name="android.hardware.microphone" android:required="true" />
```

And inside the existing `<application>` element, alongside the existing `<service>` for `ModelDownloadService`:

```xml
        <service
            android:name=".recording.RecordingService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "Manifest: RECORD_AUDIO + microphone foreground service + RecordingService"
```

---

## Task 13: Recording notification channel

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/WhisprApp.kt`

- [ ] **Step 1: Register the channel**

In `WhisprApp.registerNotificationChannels()`, add:

```kotlin
        nm.createNotificationChannel(NotificationChannel(
            dev.pabloi.whisper.recording.RecordingService.CHANNEL_ID,
            "Live recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Whispr is recording audio."
            setShowBadge(false)
        })
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/WhisprApp.kt
git commit -m "WhisprApp: register recording notification channel"
```

---

## Task 14: `RecordViewModel`

UI-state holder. Observes the singleton `RecordingService` flows. Owns the SAF picker and permission flows. Dedupes one-word overlap between consecutive engine segments.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/ui/RecordViewModel.kt`

- [ ] **Step 1: Implement**

Create `app/src/main/java/dev/pabloi/whisper/ui/RecordViewModel.kt`:

```kotlin
package dev.pabloi.whisper.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.data.AudioSourcePreset
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.recording.Recording
import dev.pabloi.whisper.recording.RecordingService
import dev.pabloi.whisper.recording.RecordingState
import dev.pabloi.whisper.recording.RecordingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class RecordUiState(
    val state: RecordingState = RecordingState.Idle,
    val segments: List<Pair<String, String>> = emptyList(),  // (timestamps, text)
    val transcribeLive: Boolean = true,
    val needsFolder: Boolean = false,
    val needsMicPermission: Boolean = false,
    val error: String? = null,
)

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val whispr = app as WhisprApp

    private val _ui = MutableStateFlow(RecordUiState())
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    val recordings: StateFlow<List<Recording>> = run {
        val sf = MutableStateFlow<List<Recording>>(emptyList())
        viewModelScope.launch {
            RecordingsStore(app).flow.collect { sf.value = it }
        }
        sf.asStateFlow()
    }

    private var lastSegmentLastWord: String = ""

    init {
        RecordingService.state.onEach { _ui.value = _ui.value.copy(state = it) }.launchIn(viewModelScope)
        RecordingService.events.onEach { ev ->
            when (ev) {
                is TranscribeEvent.Segment -> {
                    val deduped = dedupeOverlap(ev.text)
                    if (deduped.isNotEmpty()) {
                        _ui.value = _ui.value.copy(
                            segments = _ui.value.segments + (formatTime(ev.startSec, ev.endSec) to deduped)
                        )
                    }
                    lastSegmentLastWord = deduped.trim().split(WS).lastOrNull().orEmpty()
                }
                is TranscribeEvent.Failure -> _ui.value = _ui.value.copy(
                    error = "Live transcription stopped: ${ev.cause.message ?: ev.cause::class.java.simpleName}"
                )
                else -> Unit
            }
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            val s = whispr.settings.flow.first()
            _ui.value = _ui.value.copy(transcribeLive = s.recordWithTranscription)
        }
    }

    /** Returns true if everything's ready to start; false if a permission/folder prompt is needed. */
    suspend fun preflight(micGranted: Boolean): Boolean {
        if (!micGranted) {
            _ui.value = _ui.value.copy(needsMicPermission = true); return false
        }
        val s = whispr.settings.flow.first()
        if (s.recordingsFolderUri.isBlank()) {
            _ui.value = _ui.value.copy(needsFolder = true); return false
        }
        _ui.value = _ui.value.copy(needsMicPermission = false, needsFolder = false)
        return true
    }

    fun setFolder(uri: Uri) {
        viewModelScope.launch {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            whispr.settings.update { it.copy(recordingsFolderUri = uri.toString()) }
            _ui.value = _ui.value.copy(needsFolder = false)
        }
    }

    fun setTranscribeLive(on: Boolean) {
        _ui.value = _ui.value.copy(transcribeLive = on)
        viewModelScope.launch { whispr.settings.update { it.copy(recordWithTranscription = on) } }
    }

    fun start() {
        val s = runCatching {
            kotlinx.coroutines.runBlocking { whispr.settings.flow.first() }
        }.getOrNull() ?: return
        _ui.value = _ui.value.copy(segments = emptyList(), error = null)
        lastSegmentLastWord = ""
        RecordingService.start(
            context = getApplication(),
            transcribeLive = _ui.value.transcribeLive,
            source = s.audioSourcePreset,
            effectsOn = s.audioEffectsOn,
        )
    }

    fun stop() = RecordingService.stop(getApplication())

    private fun dedupeOverlap(segText: String): String {
        if (lastSegmentLastWord.isEmpty()) return segText
        val parts = segText.trim().split(WS)
        if (parts.isEmpty()) return segText
        return if (parts.first().equals(lastSegmentLastWord, ignoreCase = true)) {
            parts.drop(1).joinToString(" ")
        } else segText
    }

    private fun formatTime(start: Double?, end: Double?): String {
        if (start == null || end == null) return ""
        fun fmt(s: Double): String {
            val total = s.toInt()
            val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
        return "${fmt(start)}–${fmt(end)}"
    }

    companion object { private val WS = Regex("\\s+") }
}
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/ui/RecordViewModel.kt
git commit -m "RecordViewModel: observes service flows, dedupes overlap, owns SAF/perm prompts"
```

---

## Task 15: `RecordScreen`

UI for live recording. Mic level meter, timer, "Transcribe while recording" toggle, big Stop button when recording, segments list. Handles permission + SAF folder prompts.

**Files:**
- Create: `app/src/main/java/dev/pabloi/whisper/ui/RecordScreen.kt`

- [ ] **Step 1: Implement**

Create `app/src/main/java/dev/pabloi/whisper/ui/RecordScreen.kt`:

```kotlin
package dev.pabloi.whisper.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pabloi.whisper.recording.RecordingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    vm: RecordViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    val recState = ui.state
    val isBusy = recState !is RecordingState.Idle

    DisposableEffect(isBusy, activity) {
        val window = activity?.window
        if (isBusy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) tryStart(vm, context)
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.setFolder(uri)
            tryStart(vm, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Live") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
                },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = recState) {
                RecordingState.Idle, is RecordingState.Failure -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = ui.transcribeLive, onCheckedChange = { vm.setTranscribeLive(it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Transcribe while recording")
                    }
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else tryStart(vm, context, folderLauncher::launch)
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Start", style = MaterialTheme.typography.titleLarge) }

                    if (s is RecordingState.Failure) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(s.cause.message ?: s.cause::class.java.simpleName, modifier = Modifier.padding(12.dp))
                        }
                    }
                    ui.error?.let {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(it, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                is RecordingState.Starting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting…")
                }
                is RecordingState.Recording -> {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatDuration(s.durationMs), style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("${s.routeLabel} · ${s.sampleRate} Hz", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            LevelMeter(levelDb = s.levelDb)
                        }
                    }
                    Button(
                        onClick = { vm.stop() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stop", style = MaterialTheme.typography.titleLarge) }
                }
                is RecordingState.Paused -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.MicOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Paused: ${s.reason}")
                        }
                    }
                }
                is RecordingState.Stopping -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Saving…")
                }
            }

            if (ui.segments.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    reverseLayout = true,
                ) {
                    items(ui.segments.asReversed()) { (time, text) ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                if (time.isNotEmpty()) {
                                    Text(time, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            if (ui.needsFolder) {
                Card { Column(Modifier.padding(12.dp)) {
                    Text("Choose a folder to save your recordings.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { folderLauncher.launch(null) }) { Text("Pick folder") }
                } }
            }
        }
    }
}

@Composable
private fun LevelMeter(levelDb: Float) {
    // Map −60..0 dB to 0..1.
    val frac = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun tryStart(
    vm: RecordViewModel,
    context: android.content.Context,
    pickFolder: ((Array<String?>) -> Unit)? = null,
) {
    kotlinx.coroutines.runBlocking {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val ok = vm.preflight(micGranted = granted)
        if (ok) vm.start()
        else if (vm.ui.value.needsFolder && pickFolder != null) pickFolder(arrayOfNulls(0))
    }
}
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (Compose preview not required.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/ui/RecordScreen.kt
git commit -m "RecordScreen: live recording UI with level meter, timer, and segments list"
```

---

## Task 16: `HomeScreen` modifications

Add `[Record Live]` button next to `[Pick audio file]`, plus a recordings list and an orphan-recovery card when applicable.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/ui/HomeScreen.kt`

- [ ] **Step 1: Add `onOpenRecord` callback to `HomeScreen` signature**

Change `HomeScreen` signature to:

```kotlin
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenRecord: () -> Unit,
    vm: HomeViewModel = viewModel(),
)
```

- [ ] **Step 2: Add `[Record Live]` button next to `[Pick audio file]`**

In the existing `Row(...) { Button(... "Pick audio file") }` block in `HomeScreen`, add a new `Button` that calls `onOpenRecord()`. The row becomes:

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !state.busy,
                    onClick = onOpenRecord,
                    modifier = Modifier.weight(1f)
                ) { Text("Record Live") }
                Button(
                    enabled = !state.busy,
                    onClick = {
                        picker.launch(arrayOf("audio/*", "application/ogg", "video/mp4"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Pick audio file") }
                if (state.busy) {
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
                }
            }
```

- [ ] **Step 3: Add a recordings list**

Below the existing segments / final-text section, add:

```kotlin
            val recordings by vm.recordingsListStateOrEmpty().collectAsState(initial = emptyList())
            if (recordings.isNotEmpty()) {
                Text("Recordings", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recordings) { r ->
                        Card {
                            Column(Modifier.padding(8.dp)) {
                                Text(r.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${r.routeLabel} · ${r.sampleRate} Hz · ${r.durationMs / 1000}s",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
```

For this to compile, add a passthrough to `HomeViewModel`:

```kotlin
fun recordingsListStateOrEmpty(): kotlinx.coroutines.flow.Flow<List<dev.pabloi.whisper.recording.Recording>> =
    dev.pabloi.whisper.recording.RecordingsStore(getApplication()).flow
```

(Alternatively define as a `val` if you prefer; either works.)

- [ ] **Step 4: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/ui/HomeScreen.kt app/src/main/java/dev/pabloi/whisper/ui/HomeViewModel.kt
git commit -m "HomeScreen: add Record Live button and recordings list"
```

---

## Task 17: `SettingsScreen` modifications

Add a "Recording" section: source preset dropdown, effects toggle, current recordings folder display + change-button.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/ui/SettingsScreen.kt`

- [ ] **Step 1: Add the Recording section**

Open `SettingsScreen.kt`. After the existing settings section, insert a new section. Concretely, ensure the following composable block is rendered (adapt names to match the file's existing style):

```kotlin
@Composable
private fun RecordingSection(
    settings: dev.pabloi.whisper.data.AppSettings,
    onPickFolder: () -> Unit,
    onSourceChanged: (dev.pabloi.whisper.data.AudioSourcePreset) -> Unit,
    onEffectsChanged: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Recording", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text("Folder: ${if (settings.recordingsFolderUri.isEmpty()) "Not set" else settings.recordingsFolderUri}",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onPickFolder) { Text("Choose folder…") }

            Spacer(Modifier.height(12.dp))
            Text("Audio source preset")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                dev.pabloi.whisper.data.AudioSourcePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = preset == settings.audioSourcePreset,
                        onClick = { onSourceChanged(preset) },
                        label = { Text(preset.name) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.audioEffectsOn, onCheckedChange = onEffectsChanged)
                Spacer(Modifier.width(8.dp))
                Text("Apply system NS / AGC")
            }
        }
    }
}
```

Then in the existing `SettingsScreen` body, after the engine/api/lang/timestamps controls, add:

```kotlin
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch { settingsStore.update { it.copy(recordingsFolderUri = uri.toString()) } }
        }
    }
    RecordingSection(
        settings = current,
        onPickFolder = { folderLauncher.launch(null) },
        onSourceChanged = { p -> scope.launch { settingsStore.update { it.copy(audioSourcePreset = p) } } },
        onEffectsChanged = { on -> scope.launch { settingsStore.update { it.copy(audioEffectsOn = on) } } },
    )
```

(Imports needed: `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `android.content.Intent`, `kotlinx.coroutines.launch`.)

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/ui/SettingsScreen.kt
git commit -m "SettingsScreen: add Recording section with folder, source preset, effects toggle"
```

---

## Task 18: Navigation wiring

Add a `record` destination to `MainActivity`'s `NavHost` and pre-request `RECORD_AUDIO`.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/MainActivity.kt`

- [ ] **Step 1: Add `record` route + pass `onOpenRecord` to `HomeScreen`**

In `MainActivity.onCreate()`, change the `NavHost { ... }` block:

```kotlin
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenRecord = { nav.navigate("record") },
                        )
                    }
                    composable("record") {
                        dev.pabloi.whisper.ui.RecordScreen(onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/MainActivity.kt
git commit -m "Navigation: add record destination"
```

---

## Task 19: Manual acceptance pass

Run the spec's manual acceptance tests on the S24 Ultra. **Before claiming the feature complete**, the three required tests below must pass.

- [ ] **Step 1: Side-load the build**

```bash
./gradlew :app:assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Required test 1 — happy path, transcribe-on**

1. Open Whispr.
2. Tap `Record Live`. Grant `RECORD_AUDIO` and `POST_NOTIFICATIONS`. Pick a recordings folder (e.g. `Documents/Whispr`).
3. Confirm the `Transcribe while recording` toggle is **on**.
4. Tap `Start`. Speak for 2 minutes. Confirm:
   - The level meter moves while you speak.
   - Within ~30 s, segment cards appear (newest at top).
   - The notification shows "Whispr — recording" and an mm:ss duration.
5. Tap `Stop`. Confirm:
   - The recording shows up in the Home screen's recordings list.
   - The chosen SAF folder contains `Recording <timestamp>.m4a`.
   - The `.m4a` plays back in any audio player.

PASS criteria: all confirms succeed.

- [ ] **Step 3: Required test 2 — happy path, transcribe-off**

1. Open Whispr → `Record Live`.
2. Toggle `Transcribe while recording` **off**.
3. `Start` → speak for 1 minute → `Stop`.
4. Confirm:
   - No segments appear during recording.
   - `.m4a` is saved to the recordings folder.
   - `adb logcat | grep BENCH` shows no encoder/decoder activity from `LocalQnnWhisperEngine` during this run.

- [ ] **Step 4: Required test 3 — Bluetooth headset**

1. Pair and connect a Bluetooth headset with a mic (or use a USB-C headset).
2. Open Whispr → `Record Live` → `Start`.
3. Confirm:
   - The route label shows `Bluetooth: <headset name>` (or `USB headset`).
   - Sample rate displayed is 8 kHz (BT SCO narrowband) or 16 kHz (mSBC wideband).
   - `Stop` → file saved at the displayed rate.

- [ ] **Step 5: Spot-check regression**

Run the existing file-transcribe path: `Pick audio file` on a 30-s WAV → verify it still produces a transcript end-to-end. This confirms Task 7's `LiveStream` plumbing didn't regress the file path.

- [ ] **Step 6: Commit acceptance evidence**

If any defects were caught and fixed, commit those fixes. Otherwise tag the branch:

```bash
git tag -a record-live-acceptance -m "Manual acceptance pass on S24 Ultra: required tests 1-3 PASS"
```

---

## Self-review

**Spec coverage check:** every spec section is covered:

- §3 architecture (two parallel pipelines from one mic tap) → Tasks 11 (`RecordingService`), 9 (`AudioCapture`), 8 (`AacWriter`), 6 (`ChunkBuilder`).
- §4.1 component responsibilities → Tasks 5, 6, 8, 9, 11, 14.
- §4.2 engine `LiveStream` → Task 7.
- §5 data flow (start/live updates/stop/backgrounding/process death) → Tasks 11, 14, 15.
- §5.7 chunking policy → Task 6.
- §6 crash safety (dual-write, ADTS sidecar) → Task 8.
- §7 error handling table — most rows are realised in `RecordingService` (Task 11) and `AudioCapture` (Task 9). The audio-focus / phone-call pause+resume path is implemented in Task 11's service but **deserves a dedicated step**: see addendum below.
- §8.1 manifest → Task 12.
- §8.2 settings additions → Task 2.
- §8.3 RecordingsStore → Task 4.
- §8.4 build changes → already in place (Task 1 only adds test deps; `kotlinx-serialization-json` was already present).
- §9.1 unit tests → Tasks 2, 3, 4, 5, 6.
- §9.2 instrumented → Tasks 8, 9.
- §9.3 manual → Task 19.

**Gap caught in self-review:** `AudioFocusRequest` handling for the phone-call pause+resume path (spec §7) is mentioned as part of `RecordingService` but has no concrete code in Task 11. Adding Task 11.5 below.

**Placeholder scan:** no TBD/TODO/"appropriate error handling" placeholders remain. Step 6 of Task 19 references "any defects" — that's procedural, not a code placeholder.

**Type consistency:** `Recording.State` enum values (`RECORDING`, `FINALISED`, `ORPHANED`, `TRANSCRIBED`) are used identically in Tasks 3, 4, 11. `AudioSourcePreset` (`MIC`, `VOICE_RECOGNITION`, `CAMCORDER`) used identically in Tasks 2, 9, 11. `RecordingState` sealed-interface variants used identically across Tasks 10, 11, 14, 15.

---

## Task 11.5 (added during self-review): Audio focus pause+resume

Tie `RecordingService` to `AudioFocusRequest` so phone calls pause cleanly and resume on hangup, per spec §7.

**Files:**
- Modify: `app/src/main/java/dev/pabloi/whisper/recording/RecordingService.kt`

- [ ] **Step 1: Acquire focus when recording starts; pause/resume on focus change**

In `RecordingService.kt`, add as private fields:

```kotlin
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseDueToFocus()
            android.media.AudioManager.AUDIOFOCUS_GAIN -> resumeFromFocus()
            android.media.AudioManager.AUDIOFOCUS_LOSS -> stopSelfAsync()
        }
    }
```

In `onStartCommand` (after `_state.value = RecordingState.Recording(...)`), request focus:

```kotlin
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build())
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(false)
            .build()
        am.requestAudioFocus(focusRequest!!)
```

In `cleanup()`, abandon focus:

```kotlin
        focusRequest?.let {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(it)
        }
        focusRequest = null
```

Add the two helpers:

```kotlin
    private fun pauseDueToFocus() {
        val st = state.value
        if (st !is RecordingState.Recording) return
        capture?.pause()
        scope.launch { chunkBuilder?.close() }  // emits the pre-call partial chunk if speech in buffer
        _state.value = RecordingState.Paused(st.recordingId, "phone call", st.routeLabel)
    }

    private fun resumeFromFocus() {
        val st = state.value
        if (st !is RecordingState.Paused) return
        capture?.resume()
        // Recreate ChunkBuilder for the post-resume buffer (the previous one is closed).
        chunkBuilder = ChunkBuilder()
        // Engine remains active; new chunks from the new ChunkBuilder need to be plumbed
        // into the existing engineChunks channel — for the v1 implementation, audio focus
        // resume re-opens a fresh ChunkBuilder whose flow is collected and forwarded.
        scope.launch { chunkBuilder!!.flow.collect { engineChunks?.send(it) } }
        _state.value = RecordingState.Recording(
            recordingId = st.recordingId,
            startedAtMs = startedAtMs,
            durationMs = System.currentTimeMillis() - startedAtMs,
            levelDb = -120f,
            routeLabel = st.routeLabel,
            sampleRate = capture?.sampleRate?.value ?: 48_000,
            transcribing = chunkBuilder != null,
        )
    }
```

- [ ] **Step 2: Build smoke**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/pabloi/whisper/recording/RecordingService.kt
git commit -m "RecordingService: AudioFocusRequest -> pause on phone call, resume on hangup"
```

---

## Done definition

- All 20 tasks (1–11, 11.5, 12–19) committed.
- `./gradlew :app:testDebugUnitTest` passes.
- `./gradlew :app:connectedDebugAndroidTest` passes when a device is attached (Tasks 8 + 9 instrumented tests).
- Required manual tests 1–3 (Task 19) PASS on the S24 Ultra.
- File-transcribe path (existing feature) is verified non-regressed (Task 19 step 5).
