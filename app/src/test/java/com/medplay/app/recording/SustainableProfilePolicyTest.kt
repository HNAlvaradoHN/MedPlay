package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SustainableProfilePolicyTest {

    @Test
    fun prefers1080p30HevcHardwareWhenAvailable() {
        val supported = listOf(
            p(VideoCodec.AVC, 1920, 1080, 30, 16_000_000),
            p(VideoCodec.HEVC, 1920, 1080, 30, 12_000_000),
        )

        val chosen = SustainableProfilePolicy.chooseStartupProfile(supported)

        assertEquals(VideoCodec.HEVC, chosen?.codec)
        assertEquals(1920, chosen?.width)
        assertEquals(1080, chosen?.height)
        assertEquals(30, chosen?.fps)
        assertEquals(12_000_000, chosen?.bitrate)
    }

    @Test
    fun neverSelectsSoftwareEncoderForAutomaticStartup() {
        val supported = listOf(
            p(VideoCodec.HEVC, 1920, 1080, 30, 12_000_000, hardware = false),
            p(VideoCodec.AVC, 1280, 720, 30, 10_000_000, hardware = true),
        )

        val chosen = SustainableProfilePolicy.chooseStartupProfile(supported)

        assertEquals(VideoCodec.AVC, chosen?.codec)
        assertEquals(1280, chosen?.width)
        assertEquals(720, chosen?.height)
    }

    @Test
    fun returnsNullWhenOnlySoftwareEncodingExists() {
        val supported = listOf(
            p(VideoCodec.AVC, 1280, 720, 30, 10_000_000, hardware = false),
        )

        assertNull(SustainableProfilePolicy.chooseStartupProfile(supported))
    }

    private fun p(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        hardware: Boolean = true,
    ) = SustainableProfilePolicy.SupportedProfile(
        encoderName = if (hardware) "hardware.encoder" else "software.encoder",
        codec = codec,
        hardwareAccelerated = hardware,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
    )
}
