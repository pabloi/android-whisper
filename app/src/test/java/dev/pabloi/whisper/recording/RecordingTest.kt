package dev.pabloi.whisper.recording

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTest {

    @Test fun roundTripJson() {
        val r = Recording(
            id = "abc",
            displayName = "Recording 2026-04-27 15:30",
            audioUri = "content://tree/lecture.m4a",
            transcriptUri = "",
            startedAt = 1_745_000_000_000L,
            durationMs = 0L,
            sampleRate = 48_000,
            routeLabel = "Built-in mic",
            state = Recording.State.RECORDING,
        )
        val s = Json.encodeToString(Recording.serializer(), r)
        val r2 = Json.decodeFromString(Recording.serializer(), s)
        assertEquals(r, r2)
    }
}
