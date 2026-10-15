package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingQualityPreferenceTest {

    private fun supported(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ) = SustainableProfilePolicy.SupportedProfile(
        encoderName = "encoder",
        codec = codec,
        hardwareAccelerated = true,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
    )

    @Test
    fun supportedModesContainOnlyValidatedManualModes() {
        val modes = RecordingQualityPreference.supportedModes(
            listOf(
                supported(VideoCodec.HEVC, 1920, 1080, 30, 12_000_000),
                supported(VideoCodec.AVC, 1280, 720, 30, 10_000_000),
            )
        )

        assertEquals(
            listOf(
                RecordingQualityPreference.Mode.AUTO,
                RecordingQualityPreference.Mode.P720_30,
                RecordingQualityPreference.Mode.P1080_30,
            ),
            modes,
        )
    }

    @Test
    fun manualModePrefersHevcWhenAvailable() {
        val profile = RecordingQualityPreference.choose(
            RecordingQualityPreference.Mode.P720_60,
            listOf(
                supported(VideoCodec.AVC, 1280, 720, 60, 14_000_000),
                supported(VideoCodec.HEVC, 1280, 720, 60, 10_000_000),
            )
        )

        assertEquals(VideoCodec.HEVC, profile?.codec)
        assertEquals(60, profile?.fps)
    }

    @Test
    fun unsupportedManualModeFallsBackToAuto() {
        val supported = listOf(
            supported(VideoCodec.HEVC, 1280, 720, 30, 7_000_000)
        )

        val profile = RecordingQualityPreference.choose(
            RecordingQualityPreference.Mode.P1080_30,
            supported,
        )

        assertTrue(profile != null)
        assertEquals(1280, profile?.width)
        assertEquals(720, profile?.height)
        assertEquals(30, profile?.fps)
    }
}
