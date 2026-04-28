package dev.pabloi.whisper.audio

/**
 * A 30-s zero-padded mono f32 chunk at 16 kHz, plus the wall-clock start time
 * (in seconds, relative to the start of the input stream) of the first sample
 * in `samples`. The engine uses [startSec] for segment timestamps; for file
 * transcription it's `chunkIndex * 30.0`, for live VAD-aligned chunks it's the
 * real audio time when the chunk's first sample was captured.
 */
data class TimedChunk(val startSec: Double, val samples: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is TimedChunk && startSec == other.startSec && samples.contentEquals(other.samples)
    override fun hashCode(): Int = 31 * startSec.hashCode() + samples.contentHashCode()
}
