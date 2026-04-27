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
