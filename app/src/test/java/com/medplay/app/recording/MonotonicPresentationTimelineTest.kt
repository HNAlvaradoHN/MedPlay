package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonotonicPresentationTimelineTest {

    @Test
    fun mapsZeroBasedPtsOntoAnchor() {
        val timeline = MonotonicPresentationTimeline()
        timeline.start(1_000_000L)

        assertEquals(1_000_000L, timeline.map(0L))
        assertEquals(1_033_333L, timeline.map(33_333L))
    }

    @Test
    fun normalizesLargeAbsoluteLookingPts() {
        val timeline = MonotonicPresentationTimeline()
        timeline.start(5_000_000L)

        assertEquals(5_000_000L, timeline.map(9_000_000_000L))
        assertEquals(5_040_000L, timeline.map(9_000_040_000L))
    }

    @Test
    fun rejectsPtsRegression() {
        val timeline = MonotonicPresentationTimeline()
        timeline.start(10_000L)
        timeline.map(200L)

        assertThrows(IllegalStateException::class.java) {
            timeline.map(199L)
        }
    }
}
