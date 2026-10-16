package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPauseTimelineTest {

    @Test
    fun removesCompletedPauseFromOutputTimeline() {
        val pauses = listOf(
            RecordingPauseInterval(
                startUs = 10_000_000L,
                endUs = 15_000_000L,
            )
        )

        assertEquals(
            8_000_000L,
            RecordingPauseTimeline.outputTimeUs(
                absoluteUs = 18_000_000L,
                clipStartUs = 5_000_000L,
                pauses = pauses,
            )
        )
    }

    @Test
    fun reportsSamplesInsidePause() {
        val pauses = listOf(RecordingPauseInterval(2_000_000L, 4_000_000L))

        assertFalse(RecordingPauseTimeline.isPaused(1_999_999L, pauses))
        assertTrue(RecordingPauseTimeline.isPaused(2_000_000L, pauses))
        assertTrue(RecordingPauseTimeline.isPaused(3_000_000L, pauses))
        assertFalse(RecordingPauseTimeline.isPaused(4_000_000L, pauses))
    }

    @Test
    fun totalsOnlyPauseOverlapInsideClip() {
        val pauses = listOf(
            RecordingPauseInterval(1_000_000L, 3_000_000L),
            RecordingPauseInterval(8_000_000L, 12_000_000L),
        )

        assertEquals(
            4_000_000L,
            RecordingPauseTimeline.removedDurationUs(
                clipStartUs = 2_000_000L,
                clipEndUs = 11_000_000L,
                pauses = pauses,
            )
        )
    }
}
