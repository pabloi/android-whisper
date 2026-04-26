package dev.pabloi.whisper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.FileInputStream
import java.nio.ByteOrder

/**
 * Decodes any audio container Android's stock codecs understand
 * (wav, m4a/aac, mp3, flac, ogg/opus, webm, amr) to 16 kHz mono f32.
 *
 * Streaming-first: [streamMonoF32] emits 30-s f32 chunks as MediaCodec
 * produces samples, so memory stays bounded regardless of file length.
 * Loading 21 minutes of audio as one FloatArray (80+ MB) on Android's
 * per-app Java heap (~256–512 MB) hits the GC cap; with streaming the
 * peak per call is ~10 MB.
 *
 * MediaCodec is hardware-accelerated where supported. Resampling is a
 * simple linear resampler — fine for speech; Whisper's mel filterbank
 * is the real accuracy gate. We resample per-chunk; the 1-sample
 * boundary discontinuity at each chunk join is inaudible to STT.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    /** One-shot decode of a small file. Uses the streaming path internally. */
    suspend fun decodeToMonoF32(context: Context, source: Uri): FloatArray =
        collectAll(streamMonoF32(context, source, chunkSamples = Int.MAX_VALUE / 4))

    suspend fun decodeToMonoF32(path: String): FloatArray =
        collectAll(streamMonoF32(path, chunkSamples = Int.MAX_VALUE / 4))

    /**
     * Stream the audio as fixed-size [chunkSamples]-element f32 chunks at
     * 16 kHz mono. The last emission may be the same length but with
     * trailing zeros if the file doesn't divide evenly.
     */
    fun streamMonoF32(
        context: Context,
        source: Uri,
        chunkSamples: Int,
    ): Flow<FloatArray> = flow {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(source, "r").use { pfd ->
                requireNotNull(pfd) { "Cannot open $source" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            streamFromExtractor(extractor, chunkSamples) { emit(it) }
        } finally {
            extractor.release()
        }
    }

    fun streamMonoF32(
        path: String,
        chunkSamples: Int,
    ): Flow<FloatArray> = flow {
        val extractor = MediaExtractor()
        try {
            FileInputStream(path).use { fis ->
                extractor.setDataSource(fis.fd)
            }
            streamFromExtractor(extractor, chunkSamples) { emit(it) }
        } finally {
            extractor.release()
        }
    }

    private suspend inline fun streamFromExtractor(
        extractor: MediaExtractor,
        chunkSamples: Int,
        crossinline emit: suspend (FloatArray) -> Unit,
    ) {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found")
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        // Number of source-rate, multi-channel int16 samples per emitted
        // 16-kHz mono chunk. e.g. 30 s, 44.1 kHz stereo →
        // chunkSamples * 44100/16000 * 2 = ~2.6M shorts × 2 B = 5.3 MB peak.
        val srcSamplesPerChunk = if (chunkSamples == Int.MAX_VALUE / 4) {
            // Single-shot path: caller wants the whole file in one emission.
            // Grow as needed.
            -1
        } else {
            chunkSamples.toLong() * srcSampleRate / TARGET_SAMPLE_RATE
        }.toInt()

        val accumulator = ShortBucket(initialCapacity = if (srcSamplesPerChunk > 0) srcSamplesPerChunk * srcChannels else 1 shl 14)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
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
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        accumulator.appendFrom(shorts)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }

                // Emit ready chunks.
                if (srcSamplesPerChunk > 0) {
                    val needed = srcSamplesPerChunk * srcChannels
                    while (accumulator.size >= needed) {
                        val chunk = downmixAndResample(
                            interleaved = accumulator.data,
                            srcSamplesInChunk = srcSamplesPerChunk,
                            srcChannels = srcChannels,
                            srcSampleRate = srcSampleRate,
                            outSamples = chunkSamples,
                        )
                        emit(chunk)
                        accumulator.consumePrefix(needed)
                    }
                }
            }

            // Flush whatever remains.
            val remaining = accumulator.size
            if (remaining > 0) {
                val srcRemaining = remaining / srcChannels
                if (srcSamplesPerChunk > 0) {
                    val chunk = downmixAndResample(
                        interleaved = accumulator.data,
                        srcSamplesInChunk = srcRemaining,
                        srcChannels = srcChannels,
                        srcSampleRate = srcSampleRate,
                        outSamples = chunkSamples,  // remainder zero-padded
                    )
                    emit(chunk)
                } else {
                    // Single-shot path: emit one big chunk sized to the actual
                    // resampled output length.
                    val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE
                    val outLen = (srcRemaining / ratio).toInt()
                    val chunk = downmixAndResample(
                        interleaved = accumulator.data,
                        srcSamplesInChunk = srcRemaining,
                        srcChannels = srcChannels,
                        srcSampleRate = srcSampleRate,
                        outSamples = outLen,
                    )
                    emit(chunk)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    /**
     * Convert [srcSamplesInChunk] interleaved int16 samples (× [srcChannels])
     * to mono f32 at [TARGET_SAMPLE_RATE], placed into a fresh FloatArray of
     * size [outSamples] (zero-padded if the source is shorter than expected).
     */
    private fun downmixAndResample(
        interleaved: ShortArray,
        srcSamplesInChunk: Int,
        srcChannels: Int,
        srcSampleRate: Int,
        outSamples: Int,
    ): FloatArray {
        val mono = FloatArray(srcSamplesInChunk)
        if (srcChannels == 1) {
            for (i in 0 until srcSamplesInChunk) mono[i] = interleaved[i].toInt() / 32768f
        } else {
            val inv = 1f / (32768f * srcChannels)
            var i = 0
            var j = 0
            while (j < srcSamplesInChunk) {
                var sum = 0
                for (c in 0 until srcChannels) sum += interleaved[i + c].toInt()
                mono[j] = sum * inv
                i += srcChannels
                j++
            }
        }

        val out = FloatArray(outSamples)
        if (srcSampleRate == TARGET_SAMPLE_RATE) {
            val n = minOf(srcSamplesInChunk, outSamples)
            System.arraycopy(mono, 0, out, 0, n)
        } else {
            val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE
            val producible = minOf(outSamples, (srcSamplesInChunk / ratio).toInt())
            var k = 0
            while (k < producible) {
                val srcPos = k * ratio
                val i0 = srcPos.toInt()
                val frac = (srcPos - i0).toFloat()
                val s0 = mono[i0]
                val s1 = if (i0 + 1 < srcSamplesInChunk) mono[i0 + 1] else s0
                out[k] = s0 + (s1 - s0) * frac
                k++
            }
        }
        return out
    }

    private suspend fun collectAll(stream: Flow<FloatArray>): FloatArray {
        val parts = ArrayList<FloatArray>()
        var total = 0
        stream.collect {
            parts.add(it)
            total += it.size
        }
        val out = FloatArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    /**
     * Append-only ShortArray that grows in-place; we explicitly avoid
     * `ArrayList<Short>` because each element would autobox to a heap
     * `Short` object (~16 B/sample → hundreds of MB on long inputs).
     */
    private class ShortBucket(initialCapacity: Int) {
        var data: ShortArray = ShortArray(initialCapacity.coerceAtLeast(1024))
            private set
        var size: Int = 0
            private set

        fun appendFrom(buf: java.nio.ShortBuffer) {
            val incoming = buf.remaining()
            ensureCapacity(size + incoming)
            buf.get(data, size, incoming)
            size += incoming
        }

        fun consumePrefix(count: Int) {
            require(count <= size)
            val remaining = size - count
            if (remaining > 0) System.arraycopy(data, count, data, 0, remaining)
            size = remaining
        }

        private fun ensureCapacity(min: Int) {
            if (data.size >= min) return
            var next = data.size * 2
            while (next < min) next *= 2
            data = data.copyOf(next)
        }
    }
}
