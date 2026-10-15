package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveRecordingProfilePolicyTest {

    @Test
    fun stepsDownFrameRateBeforeResolution() {
        val current = recording(VideoCodec.HEVC, 1920, 1080, 60, 40_000_000)
        val supported = listOf(
            supported(VideoCodec.HEVC, 1920, 1080, 30, 24_000_000),
            supported(VideoCodec.HEVC, 1280, 720, 60, 20_000_000),
            supported(VideoCodec.HEVC, 1280, 720, 30, 12_000_000),
        )

        val next = AdaptiveRecordingProfilePolicy.nextLowerProfile(current, supported)

        assertEquals(1920, next?.width)
        assertEquals(1080, next?.height)
        assertEquals(30, next?.fps)
    }

    @Test
    fun preservesCodecWhenSameLowerModeExists() {
        val current = recording(VideoCodec.HEVC, 1920, 1080, 30, 24_000_000)
        val supported = listOf(
            supported(VideoCodec.AVC, 1280, 720, 60, 28_000_000),
            supported(VideoCodec.HEVC, 1280, 720, 60, 20_000_000),
        )

        val next = AdaptiveRecordingProfilePolicy.nextLowerProfile(current, supported)

        assertEquals(VideoCodec.HEVC, next?.codec)
        assertEquals(1280, next?.width)
        assertEquals(60, next?.fps)
    }

    @Test
    fun returnsNullAtLowestValidatedMode() {
        val current = recording(VideoCodec.HEVC, 1280, 720, 30, 12_000_000)

        val next = AdaptiveRecordingProfilePolicy.nextLowerProfile(
            current,
            listOf(supported(VideoCodec.HEVC, 1280, 720, 30, 12_000_000)),
        )

        assertNull(next)
    }

    private fun recording(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ) = RecordingProfile(
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
        codec = codec,
        encoderName = "hardware.encoder",
    )

    private fun supported(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ) = SustainableProfilePolicy.SupportedProfile(
        encoderName = "hardware.encoder",
        codec = codec,
        hardwareAccelerated = true,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
    )
}
