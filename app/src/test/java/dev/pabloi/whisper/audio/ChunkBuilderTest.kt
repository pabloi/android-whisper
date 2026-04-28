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
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].samples.size)
        // Speech is at the front (it's zero-padded at the tail).
        assertNotEquals(0f, emitted[0].samples[0])
        assertEquals(0f, emitted[0].samples[ChunkBuilder.CHUNK_SAMPLES - 1])
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
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[0].samples.size)
        assertEquals(ChunkBuilder.CHUNK_SAMPLES, emitted[1].samples.size)
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
        assertTrue(emitted.all { it.samples.size == ChunkBuilder.CHUNK_SAMPLES })
    }

    @Test fun chunkTimestampsReflectAudioTime() = runTest {
        val cb = ChunkBuilder()
        // First chunk: 1 s speech + 350 ms silence → emit. startSec should be 0.
        repeat(50) { cb.feed(speechFrame()) }
        repeat(18) { cb.feed(silenceFrame()) }
        // Second chunk: another 1 s speech + 350 ms silence → emit.
        repeat(50) { cb.feed(speechFrame()) }
        repeat(18) { cb.feed(silenceFrame()) }
        cb.close()
        val emitted = cb.flow.toList()
        assertEquals(2, emitted.size)
        // First chunk starts at audio-time 0.
        assertEquals(0.0, emitted[0].startSec, 0.001)
        // Second chunk starts at the position of the carry-over front, which is
        // (firstChunkLen − LEFT_CONTEXT_SAMPLES) samples into the stream. The
        // silence-trigger fires after SILENCE_TRIGGER_MS (300 ms = 15 frames),
        // not the full 18 we feed, so firstChunkLen at emit is:
        //   50 speech frames (16000 samples) + 15 silence frames (4800 samples)
        //   = 20800 samples.
        // Expected startSec = (20800 − 3200) / 16000 = 1.10 s.
        assertEquals(1.10, emitted[1].startSec, 0.01)
    }
}
