package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class FinalExportAudioPolicyTest {

    @Test
    fun mapsGameAndMicrophoneGainsInSourceOrder() {
        val gains = FinalExportAudioPolicy.gainsForTracks(
            audioTrackCount = 2,
            hasGameAudio = true,
            hasMicrophone = true,
            gameGainPercent = 50,
            microphoneGainPercent = 150,
        )

        assertEquals(listOf(50, 150), gains)
    }

    @Test
    fun mapsSingleMicrophoneTrackWhenGameAudioIsAbsent() {
        val gains = FinalExportAudioPolicy.gainsForTracks(
            audioTrackCount = 1,
            hasGameAudio = false,
            hasMicrophone = true,
            gameGainPercent = 100,
            microphoneGainPercent = 175,
        )

        assertEquals(listOf(175), gains)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingExpectedAudioTrack() {
        FinalExportAudioPolicy.gainsForTracks(
            audioTrackCount = 1,
            hasGameAudio = true,
            hasMicrophone = true,
            gameGainPercent = 100,
            microphoneGainPercent = 100,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedAudioTrack() {
        FinalExportAudioPolicy.gainsForTracks(
            audioTrackCount = 1,
            hasGameAudio = false,
            hasMicrophone = false,
            gameGainPercent = 100,
            microphoneGainPercent = 100,
        )
    }

    @Test
    fun clampsGainsToSupportedRange() {
        val gains = FinalExportAudioPolicy.gainsForTracks(
            audioTrackCount = 2,
            hasGameAudio = true,
            hasMicrophone = true,
            gameGainPercent = -20,
            microphoneGainPercent = 260,
        )

        assertEquals(listOf(0, 200), gains)
    }
}
