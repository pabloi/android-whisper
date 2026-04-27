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
