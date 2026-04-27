package dev.pabloi.whisper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsRoundTripTest {

    @Test fun defaultsAreStable() {
        val s = AppSettings()
        assertEquals("", s.recordingsFolderUri)
        assertTrue(s.recordWithTranscription)
        assertEquals(AudioSourcePreset.VOICE_RECOGNITION, s.audioSourcePreset)
        assertTrue(s.audioEffectsOn)
    }

    @Test fun copyPreservesNewFields() {
        val s = AppSettings(
            recordingsFolderUri = "content://tree/recordings",
            recordWithTranscription = false,
            audioSourcePreset = AudioSourcePreset.MIC,
            audioEffectsOn = false,
        )
        val s2 = s.copy(language = "es")
        assertEquals("content://tree/recordings", s2.recordingsFolderUri)
        assertEquals(false, s2.recordWithTranscription)
        assertEquals(AudioSourcePreset.MIC, s2.audioSourcePreset)
        assertEquals(false, s2.audioEffectsOn)
    }
}
