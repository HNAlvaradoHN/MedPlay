package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturedContentProfilePolicyTest {

    private val base = RecordingProfile(
        width = 1920,
        height = 1080,
        fps = 60,
        bitrate = 16_000_000,
        codec = VideoCodec.HEVC,
        encoderName = "encoder",
    )

    @Test
    fun tallPhoneKeepsFullScreenAspectInsteadOfForcingNineBySixteen() {
        val fitted = CapturedContentProfilePolicy.fit(
            profile = base,
            capturedWidth = 1080,
            capturedHeight = 2400,
        )

        assertEquals(864, fitted.width)
        assertEquals(1920, fitted.height)
    }

    @Test
    fun widePhoneKeepsFullScreenAspect() {
        val fitted = CapturedContentProfilePolicy.fit(
            profile = base,
            capturedWidth = 2400,
            capturedHeight = 1080,
        )

        assertEquals(1920, fitted.width)
        assertEquals(864, fitted.height)
    }

    @Test
    fun standardSixteenByNineRemainsUnchanged() {
        val fitted = CapturedContentProfilePolicy.fit(
            profile = base,
            capturedWidth = 1920,
            capturedHeight = 1080,
        )

        assertEquals(1920, fitted.width)
        assertEquals(1080, fitted.height)
    }
}
