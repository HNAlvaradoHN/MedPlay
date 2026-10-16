package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOrientationPolicyTest {

    @Test
    fun detectsPortraitDisplay() {
        assertTrue(RecordingOrientationPolicy.isPortrait(1080, 2400))
        assertFalse(RecordingOrientationPolicy.isPortrait(2400, 1080))
    }

    @Test
    fun swapsLandscapeProfileForPortrait() {
        val profile = RecordingProfile(
            width = 1920,
            height = 1080,
            fps = 60,
            bitrate = 40_000_000,
            codec = VideoCodec.HEVC,
            encoderName = "hardware.encoder",
        )

        val oriented = RecordingOrientationPolicy.orient(profile, portrait = true)

        assertEquals(1080, oriented.width)
        assertEquals(1920, oriented.height)
        assertEquals(60, oriented.fps)
        assertEquals(40_000_000, oriented.bitrate)
    }

    @Test
    fun keepsMatchingOrientationUntouched() {
        val profile = RecordingProfile(
            width = 1080,
            height = 1920,
            fps = 30,
            bitrate = 24_000_000,
            codec = VideoCodec.HEVC,
            encoderName = "hardware.encoder",
        )

        assertEquals(
            profile,
            RecordingOrientationPolicy.orient(profile, portrait = true),
        )
    }
}
