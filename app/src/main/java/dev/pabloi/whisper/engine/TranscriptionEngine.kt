package dev.pabloi.whisper.engine

import kotlinx.coroutines.flow.Flow

/**
 * Stable contract between the UI and whatever is actually running Whisper.
 *
 * Implementations today:
 *  - dev.pabloi.whisper.engine.local.LocalQnnWhisperEngine   (on-device, Hexagon NPU)
 *  - dev.pabloi.whisper.engine.remote.RemoteOpenAIEngine     (OpenAI-compatible HTTP)
 *
 * Future:
 *  - a local HTTP server wrapping LocalQnnWhisperEngine so an IME or other
 *    process can talk to it the same way it talks to a remote endpoint.
 */
interface TranscriptionEngine {
    val id: String
    val displayName: String
    suspend fun warmUp() {}
    fun transcribe(audio: AudioSource, options: TranscribeOptions): Flow<TranscribeEvent>
    fun close() {}
}

/**
 * Audio input. We accept a SAF Uri or a file path — the audio decoder
 * opens whatever it is and resamples to 16 kHz mono f32 before anything
 * model-side runs.
 */
sealed interface AudioSource {
    data class Uri(val uri: android.net.Uri) : AudioSource
    data class File(val path: String) : AudioSource
    /** Already-decoded 16k mono f32. Useful for tests and future streaming. */
    data class Pcm(val samples: FloatArray) : AudioSource {
        override fun equals(other: Any?): Boolean =
            other is Pcm && samples.contentEquals(other.samples)
        override fun hashCode(): Int = samples.contentHashCode()
    }
}

data class TranscribeOptions(
    /** BCP-47 language hint, or null to let Whisper detect. */
    val language: String? = null,
    val task: Task = Task.TRANSCRIBE,
    /** If true, request per-segment timestamps. Local engine emits them natively. */
    val timestamps: Boolean = false,
) {
    enum class Task { TRANSCRIBE, TRANSLATE }
}

/**
 * Events are a Flow so progress, partial text, and final text can all
 * reach the UI without blocking. Local engine emits one Segment per
 * 30-second chunk; remote engine typically emits a single Final.
 */
sealed interface TranscribeEvent {
    data class Progress(val fraction: Float, val message: String? = null) : TranscribeEvent
    data class Segment(val text: String, val startSec: Double?, val endSec: Double?) : TranscribeEvent
    data class Final(val text: String, val durationMs: Long) : TranscribeEvent
    data class Failure(val cause: Throwable) : TranscribeEvent
}
