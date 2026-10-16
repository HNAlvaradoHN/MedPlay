package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipLengthPreferenceTest {

    @Test
    fun exposesOnlyShortRetroactiveDurations() {
        assertEquals(listOf(15, 30, 60), ClipLengthPreference.allowedSeconds)
        ClipLengthPreference.allowedSeconds.forEach { seconds ->
            assertEquals(seconds, ClipLengthPreference.sanitize(seconds))
        }
    }

    @Test
    fun fallsBackToThirtySecondsForUnsupportedValue() {
        assertEquals(30, ClipLengthPreference.sanitize(999))
    }
}
