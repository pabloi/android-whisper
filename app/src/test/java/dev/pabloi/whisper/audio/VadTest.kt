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
