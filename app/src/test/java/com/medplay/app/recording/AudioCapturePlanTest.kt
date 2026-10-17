package com.medplay.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapturePlanTest {

    @Test
    fun systemOnlyRecordsPlaybackWithoutReferenceOnlyMode() {
        val plan = AudioCapturePlan.decide(
            internalAudioEnabled = true,
            softwareAecRequested = false,
        )

        assertTrue(plan.capturePlayback)
        assertTrue(plan.recordInternalAudio)
        assertFalse(plan.referenceOnly)
    }

    @Test
    fun voiceOnlyCapturesPlaybackAsHiddenAecReference() {
        val plan = AudioCapturePlan.decide(
            internalAudioEnabled = false,
            softwareAecRequested = true,
        )

        assertTrue(plan.capturePlayback)
        assertFalse(plan.recordInternalAudio)
        assertTrue(plan.referenceOnly)
    }

    @Test
    fun bothRecordsPlaybackAndUsesSameCaptureForAecReference() {
        val plan = AudioCapturePlan.decide(
            internalAudioEnabled = true,
            softwareAecRequested = true,
        )

        assertTrue(plan.capturePlayback)
        assertTrue(plan.recordInternalAudio)
        assertFalse(plan.referenceOnly)
    }

    @Test
    fun noAudioDoesNotStartPlaybackCapture() {
        val plan = AudioCapturePlan.decide(
            internalAudioEnabled = false,
            softwareAecRequested = false,
        )

        assertFalse(plan.capturePlayback)
        assertFalse(plan.recordInternalAudio)
        assertFalse(plan.referenceOnly)
    }
}
