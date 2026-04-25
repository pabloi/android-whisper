package dev.pabloi.whisper.engine.local

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper-large-v3 log-mel spectrogram. Output shape is [N_MELS, N_FRAMES]
 * where N_FRAMES = 3000 for a 30-second window at 16 kHz.
 *
 * This is a direct port of OpenAI's reference preprocessing:
 *   https://github.com/openai/whisper/blob/main/whisper/audio.py
 *
 * - n_fft = 400, hop = 160, Hann window
 * - 128 mel bins (v3 variant; v1/v2 used 80)
 * - Slaney-normalized triangular mel filterbank (librosa default)
 * - log10, clamped to max(log) - 8, scaled as (log + 4) / 4
 *
 * The DFT is computed via a precomputed twiddle matrix (400 samples
 * -> 201 real/imag bins). ~240 M complex MACs per 30 s chunk; measured
 * comfortably under 1 s on the S24 Ultra's CPU, which is fine given
 * encoder cost dominates. If mel ever becomes the bottleneck, swap this
 * for a mixed-radix FFT or push it behind JNI.
 */
class MelSpectrogram(
    private val nMels: Int = N_MELS_V3,
    private val nFft: Int = N_FFT,
    private val hop: Int = HOP,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS_V3 = 128
        const val CHUNK_SECONDS = 30
        const val N_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS         // 480000
        const val N_FRAMES = N_SAMPLES / HOP                      // 3000
    }

    private val nBins: Int = nFft / 2 + 1                         // 201
    private val window: FloatArray = hannWindow(nFft)
    private val twiddleCos: FloatArray
    private val twiddleSin: FloatArray
    private val melFilter: FloatArray                             // [nMels * nBins], row-major

    init {
        // Twiddle factors w[k,n] = exp(-2πi·k·n / N_FFT), only for k in [0, nBins).
        twiddleCos = FloatArray(nBins * nFft)
        twiddleSin = FloatArray(nBins * nFft)
        for (k in 0 until nBins) {
            val base = -2.0 * PI * k / nFft
            var off = k * nFft
            for (n in 0 until nFft) {
                twiddleCos[off] = cos(base * n).toFloat()
                twiddleSin[off] = sin(base * n).toFloat()
                off++
            }
        }
        melFilter = buildMelFilterbank(sampleRate, nFft, nMels, nBins)
    }

    /**
     * Compute log-mel for an audio buffer. If `audio` is shorter than N_SAMPLES,
     * it is zero-padded on the right. If longer, it's truncated. Returns a
     * FloatArray of length nMels * N_FRAMES, row-major by mel bin.
     */
    fun compute(audio: FloatArray): FloatArray {
        val padded = FloatArray(N_SAMPLES)
        val take = min(audio.size, N_SAMPLES)
        System.arraycopy(audio, 0, padded, 0, take)

        // Reflective pad by n_fft/2 on each side — matches torch.stft(center=True).
        val halfFft = nFft / 2
        val extended = FloatArray(N_SAMPLES + nFft)
        for (i in 0 until halfFft) extended[i] = padded[halfFft - i]
        System.arraycopy(padded, 0, extended, halfFft, N_SAMPLES)
        for (i in 0 until halfFft) extended[N_SAMPLES + halfFft + i] = padded[N_SAMPLES - 2 - i]

        // Power spectrogram [nBins, N_FRAMES]
        val power = FloatArray(nBins * N_FRAMES)
        val frame = FloatArray(nFft)
        val rePart = FloatArray(nBins)
        val imPart = FloatArray(nBins)
        for (t in 0 until N_FRAMES) {
            val start = t * hop
            for (i in 0 until nFft) frame[i] = extended[start + i] * window[i]
            // DFT
            java.util.Arrays.fill(rePart, 0f)
            java.util.Arrays.fill(imPart, 0f)
            for (k in 0 until nBins) {
                var re = 0f
                var im = 0f
                val off = k * nFft
                for (n in 0 until nFft) {
                    val s = frame[n]
                    re += s * twiddleCos[off + n]
                    im += s * twiddleSin[off + n]
                }
                rePart[k] = re
                imPart[k] = im
            }
            for (k in 0 until nBins) {
                val re = rePart[k]
                val im = imPart[k]
                power[k * N_FRAMES + t] = re * re + im * im
            }
        }

        // Mel projection: mel[m,t] = sum_k melFilter[m,k] * power[k,t]
        val mel = FloatArray(nMels * N_FRAMES)
        for (m in 0 until nMels) {
            val filterRow = m * nBins
            val melRow = m * N_FRAMES
            for (t in 0 until N_FRAMES) {
                var acc = 0f
                for (k in 0 until nBins) {
                    acc += melFilter[filterRow + k] * power[k * N_FRAMES + t]
                }
                mel[melRow + t] = acc
            }
        }

        // log10 with Whisper's clipping and rescaling.
        var logMax = Float.NEGATIVE_INFINITY
        for (i in mel.indices) {
            val v = max(mel[i], 1e-10f)
            val lg = (log10(v.toDouble())).toFloat()
            mel[i] = lg
            if (lg > logMax) logMax = lg
        }
        val floor = logMax - 8f
        for (i in mel.indices) {
            val v = max(mel[i], floor)
            mel[i] = (v + 4f) / 4f
        }
        return mel
    }

    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 - 0.5 * cos(2.0 * PI * i / n)).toFloat()
        return w
    }

    /**
     * Slaney-normalized triangular mel filterbank, matching librosa's default
     * (htk=False, norm="slaney"). Whisper's reference mel_filters.npz is
     * produced this way.
     */
    private fun buildMelFilterbank(sr: Int, nFft: Int, nMels: Int, nBins: Int): FloatArray {
        val fMin = 0.0
        val fMax = sr / 2.0
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        val mels = DoubleArray(nMels + 2)
        for (i in 0 until nMels + 2) mels[i] = melMin + (melMax - melMin) * i / (nMels + 1)
        val hzPts = DoubleArray(nMels + 2)
        for (i in mels.indices) hzPts[i] = melToHz(mels[i])

        val fftFreqs = DoubleArray(nBins)
        for (i in 0 until nBins) fftFreqs[i] = sr.toDouble() * i / nFft

        val filter = FloatArray(nMels * nBins)
        for (m in 0 until nMels) {
            val lower = hzPts[m]
            val center = hzPts[m + 1]
            val upper = hzPts[m + 2]
            val norm = 2.0 / (upper - lower) // Slaney normalization
            val row = m * nBins
            for (k in 0 until nBins) {
                val f = fftFreqs[k]
                val w = when {
                    f < lower || f > upper -> 0.0
                    f <= center -> (f - lower) / (center - lower)
                    else -> (upper - f) / (upper - center)
                }
                filter[row + k] = (w * norm).toFloat()
            }
        }
        return filter
    }

    // librosa-compatible (htk=False) mel scale.
    private fun hzToMel(hz: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var mel = (hz - fMin) / fSp
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (hz >= minLogHz) mel = minLogMel + ln(hz / minLogHz) / logStep
        return mel
    }

    private fun melToHz(mel: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var hz = fMin + fSp * mel
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (mel >= minLogMel) hz = minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))
        return hz
    }
}
