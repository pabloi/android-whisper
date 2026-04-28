package dev.pabloi.whisper.audio

/**
 * A 30-s zero-padded mono f32 chunk at 16 kHz, plus the wall-clock start time
 * (in seconds, relative to the start of the input stream) of the first sample
 * in `samples`, and the actual audio duration of the unpadded content. The
 * engine uses [startSec]/[durationSec] for segment timestamps; for file
 * transcription [durationSec] is `30.0`, for live VAD-aligned chunks it's the
 * real captured length before zero-padding (typically 1–10 s).
 */
data class TimedChunk(
    val startSec: Double,
    val durationSec: Double,
    val samples: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TimedChunk && startSec == other.startSec && durationSec == other.durationSec && samples.contentEquals(other.samples)
    override fun hashCode(): Int {
        var h = startSec.hashCode()
        h = 31 * h + durationSec.hashCode()
        h = 31 * h + samples.contentHashCode()
        return h
    }
}
