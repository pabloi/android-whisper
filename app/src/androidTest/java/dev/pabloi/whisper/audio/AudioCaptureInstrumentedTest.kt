package dev.pabloi.whisper.audio

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.pabloi.whisper.data.AudioSourcePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AudioCaptureInstrumentedTest {

    @get:Rule val permRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test fun openAndCaptureOneFrame() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cap = AudioCapture(ctx, AudioSourcePreset.VOICE_RECOGNITION, effectsOn = true)
        cap.open()
        try {
            val frame = withTimeout(5_000) { cap.frames.first() }
            assertNotNull(frame.pcm)
            assertTrue(frame.pcm.isNotEmpty())
            assertTrue(frame.sampleRate >= 8_000)
        } finally {
            cap.close()
        }
    }
}
