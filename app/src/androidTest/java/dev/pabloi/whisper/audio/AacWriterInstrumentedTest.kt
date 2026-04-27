package dev.pabloi.whisper.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AacWriterInstrumentedTest {

    @Test fun encodesAndProducesValidM4a() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outFile = File.createTempFile("aac_test_", ".m4a", ctx.cacheDir)
        val pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        val sidecar = ByteArrayOutputStream()
        val writer = AacWriter(pfd, sidecar, sampleRate = 48_000, channels = 1, bitRate = 64_000)

        // 5 s of 440 Hz sine at amp 0.3 -> int16 LE mono.
        val samples = 48_000 * 5
        val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val v = (0.3 * sin(2 * PI * 440 * i / 48_000.0) * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(v)
        }
        buf.flip()
        writer.append(buf)
        writer.close()
        pfd.close()

        // The .m4a should now be parseable by MediaExtractor.
        val ext = MediaExtractor()
        ext.setDataSource(outFile.absolutePath)
        assertTrue(ext.trackCount > 0)
        val fmt = ext.getTrackFormat(0)
        assertEquals("audio/mp4a-latm", fmt.getString(MediaFormat.KEY_MIME))
        assertEquals(48_000, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        ext.release()

        // Sidecar should have ADTS frames (each starts with 0xFFF sync word).
        val sidecarBytes = sidecar.toByteArray()
        assertTrue("sidecar empty", sidecarBytes.isNotEmpty())
        assertEquals(0xFF.toByte(), sidecarBytes[0])
        assertEquals(0xF1.toByte(), sidecarBytes[1])
    }
}
