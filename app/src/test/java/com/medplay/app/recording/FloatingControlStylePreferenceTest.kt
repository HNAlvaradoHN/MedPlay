package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlStylePreferenceTest {

    @Test
    fun clampsFloatingSizeToSupportedRange() {
        assertEquals(
            FloatingControlStylePreference.MIN_SIZE_DP,
            FloatingControlStylePreference.clampSize(1),
        )
        assertEquals(
            FloatingControlStylePreference.DEFAULT_SIZE_DP,
            FloatingControlStylePreference.clampSize(
                FloatingControlStylePreference.DEFAULT_SIZE_DP
            ),
        )
        assertEquals(
            FloatingControlStylePreference.MAX_SIZE_DP,
            FloatingControlStylePreference.clampSize(500),
        )
    }

    @Test
    fun clampsOpacityToReadableGlassRange() {
        assertEquals(
            FloatingControlStylePreference.MIN_OPACITY_PERCENT,
            FloatingControlStylePreference.clampOpacity(0),
        )
        assertEquals(
            FloatingControlStylePreference.DEFAULT_OPACITY_PERCENT,
            FloatingControlStylePreference.clampOpacity(
                FloatingControlStylePreference.DEFAULT_OPACITY_PERCENT
            ),
        )
        assertEquals(
            FloatingControlStylePreference.MAX_OPACITY_PERCENT,
            FloatingControlStylePreference.clampOpacity(100),
        )
    }
}
