package dev.pabloi.whisper.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Streaming PCM-to-AAC encoder.
 *
 *   - `m4aFd`     : the SAF-supplied PFD where MediaMuxer builds an MP4 container.
 *                    `moov` is only written on close(), so a hard kill mid-record
 *                    leaves an unplayable .m4a — that's what the sidecar is for.
 *   - `adtsOut`   : OutputStream to a sidecar dotfile. We tee the same encoded
 *                    AAC frames here, prefixed with a 7-byte ADTS header. ADTS
 *                    streams are self-describing — every frame is independently
 *                    decodable, no index needed. On orphan recovery this is the
 *                    source of truth; remux to .m4a is one-pass.
 *
 * Thread model: `append()` and `close()` are NOT thread-safe; the service serialises
 * calls onto its writer coroutine.
 */
class AacWriter(
    m4aFd: ParcelFileDescriptor,
    private val adtsOut: OutputStream,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitRate: Int = chooseBitRate(sampleRate),
) : AutoCloseable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME).apply {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val muxer: MediaMuxer = MediaMuxer(m4aFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var muxerTrack: Int = -1
    private var muxerStarted = false
    private var totalSamples: Long = 0L

    /** Append PCM 16-bit little-endian mono samples. */
    fun append(pcm: ByteBuffer) {
        // Drain any already-encoded output before pushing more input, so the
        // encoder doesn't stall on a full output queue.
        drain(eos = false)

        while (pcm.hasRemaining()) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) { drain(eos = false); continue }
            val inBuf = codec.getInputBuffer(inIdx)!!
            inBuf.clear()
            val toCopy = minOf(inBuf.remaining(), pcm.remaining())
            val limitOld = pcm.limit()
            pcm.limit(pcm.position() + toCopy)
            inBuf.put(pcm)
            pcm.limit(limitOld)

            val ptsUs = totalSamples * 1_000_000 / sampleRate
            codec.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
            totalSamples += toCopy / 2 / channels  // 16-bit samples
        }
    }

    private fun drain(eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        if (eos) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        loop@ while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFmt = codec.outputFormat
                    muxerTrack = muxer.addTrack(outFmt)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) break@loop else break@loop
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        if (muxerStarted) muxer.writeSampleData(muxerTrack, outBuf, info)
                        // ADTS sidecar: prefix each AAC frame with a 7-byte header.
                        outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                        val header = adtsHeader(packetLength = 7 + info.size, sampleRate, channels)
                        adtsOut.write(header)
                        val raw = ByteArray(info.size); outBuf.get(raw)
                        adtsOut.write(raw)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break@loop
                }
                else -> if (!eos) break@loop
            }
        }
    }

    override fun close() {
        runCatching { drain(eos = true) }
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { adtsOut.flush(); adtsOut.close() }
    }

    companion object {
        private const val MIME = "audio/mp4a-latm"

        /**
         * Build the 7-byte ADTS header for a given payload+header length, sample rate
         * and channel count. AAC-LC profile (object type 2). MPEG-4. No CRC.
         */
        fun adtsHeader(packetLength: Int, sampleRate: Int, channels: Int): ByteArray {
            val freqIdx = freqIndex(sampleRate)
            val h = ByteArray(7)
            h[0] = 0xFF.toByte()
            h[1] = 0xF1.toByte()    // MPEG-4, no CRC
            h[2] = (((2 - 1) shl 6) or (freqIdx shl 2) or ((channels shr 2) and 0x1)).toByte()
            h[3] = (((channels and 0x3) shl 6) or ((packetLength shr 11) and 0x3)).toByte()
            h[4] = ((packetLength shr 3) and 0xFF).toByte()
            h[5] = (((packetLength and 0x7) shl 5) or 0x1F).toByte()
            h[6] = 0xFC.toByte()
            return h
        }

        private fun freqIndex(sr: Int): Int = when (sr) {
            96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
            32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
            11_025 -> 10; 8_000 -> 11
            else -> error("Unsupported sample rate $sr for AAC ADTS")
        }

        private fun chooseBitRate(sr: Int): Int = when {
            sr >= 32_000 -> 64_000
            sr >= 16_000 -> 32_000
            else -> 24_000
        }
    }
}
