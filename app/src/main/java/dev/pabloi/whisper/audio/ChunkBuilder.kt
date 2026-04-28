package dev.pabloi.whisper.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Accepts continuous 16-kHz mono f32 frames (one [Vad.FRAME_SAMPLES] frame per call),
 * emits 30-s zero-padded chunks on:
 *   - silence >= [SILENCE_TRIGGER_MS] after any detected speech in the buffer, or
 *   - buffer reaches [CHUNK_SAMPLES] (force-fire).
 * Skips pure-silence buffers. Carries [LEFT_CONTEXT_SAMPLES] across cuts so a word
 * cut by a force-fire reappears whole at the start of the next chunk.
 *
 * The output [flow] completes when [close] is called.
 */
class ChunkBuilder(private val vad: Vad = Vad()) {

    private val buffer = FloatArray(CHUNK_SAMPLES)
    private var len = 0                   // valid samples in buffer (samples written so far)
    private var bufferStartSamples: Long = 0L  // stream position of buffer[0]
    private var hasSpeech = false
    private var trailingSilenceFrames = 0 // consecutive silence frames at the tail

    private val channel = Channel<TimedChunk>(capacity = 16)
    val flow: Flow<TimedChunk> = channel.consumeAsFlow()

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
            if (remaining > 0) {
                System.arraycopy(frame, space, buffer, len, remaining)
                len += remaining
            }
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
        channel.close()
    }

    private suspend fun emitAndReset(forceFire: Boolean) {
        if (!hasSpeech) {
            // Pure-silence buffer: discard. Advance the stream pointer by `len`
            // so the audio-time accounting stays correct across silent stretches.
            bufferStartSamples += len.toLong()
            len = 0; trailingSilenceFrames = 0
            return
        }
        // Build a 30-s zero-padded snapshot. Duration reflects only the
        // unpadded content — the silence-trigger window included.
        val out = FloatArray(CHUNK_SAMPLES)
        System.arraycopy(buffer, 0, out, 0, len)
        val startSec = bufferStartSamples / Vad.SAMPLE_RATE_HZ.toDouble()
        val durationSec = len / Vad.SAMPLE_RATE_HZ.toDouble()
        channel.send(TimedChunk(startSec, durationSec, out))

        // Carry the last LEFT_CONTEXT_SAMPLES into the next buffer.
        val keepFrom = (len - LEFT_CONTEXT_SAMPLES).coerceAtLeast(0)
        val keep = len - keepFrom
        if (keep > 0) System.arraycopy(buffer, keepFrom, buffer, 0, keep)
        // The new buffer's first sample sits at stream position
        // (oldBufferStart + len_at_emit - keep).
        bufferStartSamples += (len - keep).toLong()
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
