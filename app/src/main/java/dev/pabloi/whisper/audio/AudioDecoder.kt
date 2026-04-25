package dev.pabloi.whisper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio container Android's stock codecs understand
 * (wav, m4a/aac, mp3, flac, ogg/opus, webm, amr) to 16 kHz mono f32.
 *
 * We rely on MediaCodec so decoding is hardware-accelerated where the
 * device supports it. Resampling is a simple linear resampler — it's
 * fine for speech; Whisper's mel filterbank is the real accuracy gate.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    fun decodeToMonoF32(context: Context, source: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(source, "r").use { pfd ->
                requireNotNull(pfd) { "Cannot open $source" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            return decode(extractor)
        } finally {
            extractor.release()
        }
    }

    fun decodeToMonoF32(path: String): FloatArray {
        val extractor = MediaExtractor()
        try {
            FileInputStream(path).use { fis ->
                extractor.setDataSource(fis.fd)
            }
            return decode(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor): FloatArray {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found")
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, /*surface=*/ null, /*crypto=*/ null, /*flags=*/ 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcm16 = ArrayList<Short>(srcSampleRate * 30) // rough preallocation
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val ts = extractor.sampleTime
                        codec.queueInputBuffer(inIdx, 0, size, ts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shorts.hasRemaining()) pcm16.add(shorts.get())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Some decoders report true channel count here; MediaFormat already
                    // gave us one, but we honor the updated value if the decoder changes it.
                }
            }
        }

        codec.stop()
        codec.release()

        // Interleaved PCM16 -> mono f32
        val mono = FloatArray(pcm16.size / srcChannels)
        if (srcChannels == 1) {
            for (i in mono.indices) mono[i] = pcm16[i].toInt() / 32768f
        } else {
            var i = 0
            var j = 0
            val inv = 1f / (32768f * srcChannels)
            while (j < mono.size) {
                var sum = 0
                for (c in 0 until srcChannels) { sum += pcm16[i + c].toInt() }
                mono[j] = sum * inv
                i += srcChannels
                j++
            }
        }

        return if (srcSampleRate == TARGET_SAMPLE_RATE) mono
        else linearResample(mono, srcSampleRate, TARGET_SAMPLE_RATE)
    }

    /**
     * Simple linear resampler. Whisper is trained at 16 kHz; 44.1 or 48 kHz
     * source material is the common case. Linear is lossier than polyphase
     * but the mel filterbank smooths most of it out for speech. If we need
     * cleaner resampling later, swap for a windowed-sinc implementation.
     */
    private fun linearResample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (src.size / ratio).toInt()
        val out = FloatArray(outLen)
        var i = 0
        while (i < outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val frac = (srcPos - i0).toFloat()
            val s0 = src[i0]
            val s1 = if (i0 + 1 < src.size) src[i0 + 1] else s0
            out[i] = s0 + (s1 - s0) * frac
            i++
        }
        return out
    }
}
