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
