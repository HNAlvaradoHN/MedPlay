package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordClockTest {

    @Test
    fun fallbackAnchorsToFirstFrameInsteadOfReadCompletion() {
        val startUs = AudioRecordClock.estimateStartFromReadCompletionUs(
            readCompletedUs = 1_000_000L,
            framesRead = 2_048L,
            sampleRate = 48_000,
        )

        assertEquals(957_334L, startUs)
    }

    @Test
    fun differentReadSizesStayAlignedWhenTheyCoverSameCaptureStart() {
        val playbackStartUs = AudioRecordClock.estimateStartFromReadCompletionUs(
            readCompletedUs = 1_042_666L,
            framesRead = 2_048L,
            sampleRate = 48_000,
        )
        val microphoneStartUs = AudioRecordClock.estimateStartFromReadCompletionUs(
            readCompletedUs = 1_010_000L,
            framesRead = 480L,
            sampleRate = 48_000,
        )

        assertEquals(1_000_000L, playbackStartUs)
        assertEquals(1_000_000L, microphoneStartUs)
    }
}
