package com.medplay.app.recording

import org.junit.Assert.assertThrows
import org.junit.Test

class ClipTimelineValidatorTest {

    @Test
    fun acceptsOrderedNonOverlappingRanges() {
        ClipTimelineValidator.validateOrderedNonOverlapping(
            listOf(
                ClipTimelineValidator.Range(100L, 199L),
                ClipTimelineValidator.Range(200L, 299L),
                ClipTimelineValidator.Range(300L, 399L),
            )
        )
    }

    @Test
    fun rejectsOverlappingRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipTimelineValidator.validateOrderedNonOverlapping(
                listOf(
                    ClipTimelineValidator.Range(100L, 250L),
                    ClipTimelineValidator.Range(200L, 300L),
                )
            )
        }
    }

    @Test
    fun rejectsReversedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipTimelineValidator.validateOrderedNonOverlapping(
                listOf(ClipTimelineValidator.Range(500L, 400L))
            )
        }
    }
}
