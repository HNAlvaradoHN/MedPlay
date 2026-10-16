package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipEditProjectPolicyTest {

    @Test
    fun clampsTrimToSourceDuration() {
        val values = ClipEditProjectPolicy.sanitize(
            trimStartUs = -1_000L,
            trimEndUs = 30_000_000L,
            sourceDurationUs = 20_000_000L,
            gameAudioGainPercent = 100,
            microphoneGainPercent = 100,
        )

        assertEquals(0L, values.trimStartUs)
        assertEquals(20_000_000L, values.trimEndUs)
    }

    @Test
    fun keepsEndAtOrAfterStart() {
        val values = ClipEditProjectPolicy.sanitize(
            trimStartUs = 8_000_000L,
            trimEndUs = 4_000_000L,
            sourceDurationUs = 10_000_000L,
            gameAudioGainPercent = 100,
            microphoneGainPercent = 100,
        )

        assertEquals(8_000_000L, values.trimStartUs)
        assertEquals(8_000_000L, values.trimEndUs)
    }

    @Test
    fun clampsAudioGains() {
        val values = ClipEditProjectPolicy.sanitize(
            trimStartUs = 0L,
            trimEndUs = 10_000_000L,
            sourceDurationUs = 10_000_000L,
            gameAudioGainPercent = -20,
            microphoneGainPercent = 260,
        )

        assertEquals(0, values.gameAudioGainPercent)
        assertEquals(200, values.microphoneGainPercent)
    }
}
