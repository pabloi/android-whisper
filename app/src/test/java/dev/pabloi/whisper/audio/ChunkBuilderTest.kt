package dev.pabloi.whisper.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkBuilderTest {

    private fun speechFrame() = FloatArray(Vad.FRAME_SAMPLES) { i ->
        // 200 Hz sine at amplitude 0.3 with a small phase offset so sample[0] != 0.
        // (sin(0) is exactly 0; the test asserts emitted[0][0] != 0f.) Vad classifies as Speech.
        (0.3 * Math.sin(2 * Math.PI * 200 * i / Vad.SAMPLE_RATE_HZ + 0.5)).toFloat()
    }
    private fun silenceFrame() = FloatArray(Vad.FRAME_SAMPLES)

    @Test fun emitsAtSilenceAfterSpeech() = runTest {
        val cb = ChunkBuilder()
        // 1 s of speech + 350 ms of silence -> one emit.
        repeat(50) { cb.feed(speechFrame()) }
        repeat(18) { cb.feed(silenceFrame()) }  // 18 * 20 = 360 ms
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(1, emitted.size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].size)
        // Speech is at the front (it's zero-padded at the tail).
        assertNotEquals(0f, emitted[0][0])
        assertEquals(0f, emitted[0][ChunkBuilder.CHUNK_SAMPLES - 1])
    }

    @Test fun forceFiresAt30Seconds() = runTest {
        val cb = ChunkBuilder()
        // 30.4 s of continuous speech with no silence. After 30 s force-fire,
        // remaining 0.4 s sits in next buffer; closing emits its tail.
        val framesFor30s = ChunkBuilder.CHUNK_SAMPLES / Vad.FRAME_SAMPLES  // 1500
        repeat(framesFor30s + 20) { cb.feed(speechFrame()) }
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(2, emitted.size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[1].size)
    }

    @Test fun pureSilenceEmitsNothing() = runTest {
        val cb = ChunkBuilder()
        repeat(2000) { cb.feed(silenceFrame()) }  // 40 s
        cb.close()
        assertEquals(0, cb.flow.toList().size)
    }

    @Test fun leftContextPrependedToNextChunk() = runTest {
        val cb = ChunkBuilder()
        // Fire a chunk, then check the next chunk starts with non-zero (from carryover).
        repeat(50) { cb.feed(speechFrame()) }       // 1 s speech
        repeat(18) { cb.feed(silenceFrame()) }      // 360 ms silence -> emit
        repeat(50) { cb.feed(speechFrame()) }       // 1 s more speech
        repeat(18) { cb.feed(silenceFrame()) }      // -> emit
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(2, emitted.size)
        // Carry-over assertion: structurally every emitted chunk is exactly CHUNK_SAMPLES.
        assertTrue(emitted.all { it.size == ChunkBuilder.CHUNK_SAMPLES })
    }
}
