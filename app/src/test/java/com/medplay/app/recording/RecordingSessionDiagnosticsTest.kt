package com.medplay.app.recording

import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionDiagnosticsTest {

    @Test
    fun rendersCompactLocalSummary() {
        val diagnostics = RecordingSessionDiagnostics(
            startedMonotonicUs = 1_000_000L,
            endedMonotonicUs = 4_000_000L,
            profile = RecordingProfile(
                width = 1920,
                height = 1080,
                fps = 30,
                bitrate = 12_000_000,
                codec = VideoCodec.HEVC,
                encoderName = "test.encoder",
            ),
            retentionSeconds = 300,
            maxThermalStatus = 3,
            audioWasActive = true,
            encoderHealth = EncoderRuntimeHealth.Snapshot(
                encodedSamples = 90,
                timestampRegressions = 0,
                maxWallOutputGapUs = 50_000,
                timeSinceLastOutputUs = 10_000,
            ),
            microphoneWasActive = true,
            microphoneWasSilenced = true,
            microphoneAecAvailable = true,
            microphoneAecEnabled = true,
            microphoneAecMode = "webrtc_aec3",
            microphoneAecRenderFrames = 300,
            microphoneAecCaptureFrames = 295,
            microphoneAecDroppedRenderFrames = 4,
            microphoneAecResets = 1,
            microphoneAecEchoReturnLossDb = 9.5,
            microphoneAecEchoReturnLossEnhancementDb = 21.0,
            microphoneAecEstimatedDelayMs = 54,
            thermalProfileDowngrades = 1,
            initialProfile = RecordingProfile(
                width = 1920,
                height = 1080,
                fps = 60,
                bitrate = 40_000_000,
                codec = VideoCodec.HEVC,
                encoderName = "test.encoder",
            ),
        )

        val text = diagnostics.toText()

        assertTrue(text.contains("durationUs=3000000"))
        assertTrue(text.contains("resolution=1920x1080"))
        assertTrue(text.contains("encodedSamples=90"))
        assertTrue(text.contains("timestampRegressions=0"))
        assertTrue(text.contains("microphoneWasActive=true"))
        assertTrue(text.contains("microphoneWasSilenced=true"))
        assertTrue(text.contains("microphoneAecMode=webrtc_aec3"))
        assertTrue(text.contains("microphoneAecRenderFrames=300"))
        assertTrue(text.contains("microphoneAecCaptureFrames=295"))
        assertTrue(text.contains("microphoneAecDroppedRenderFrames=4"))
        assertTrue(text.contains("microphoneAecEstimatedDelayMs=54"))
        assertTrue(text.contains("initialFps=60"))
        assertTrue(text.contains("thermalProfileDowngrades=1"))
    }
}
