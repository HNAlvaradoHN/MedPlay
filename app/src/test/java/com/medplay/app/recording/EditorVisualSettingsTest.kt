package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorVisualSettingsTest {

    @Test
    fun clampsManualAdjustmentsAndNormalizesRotation() {
        val safe = EditorVisualSettings.sanitize(
            EditorVisualSettings.Values(
                filterPreset = EditorVisualSettings.FilterPreset.VIVID,
                saturationPercent = 260,
                brightnessPercent = -90,
                contrastPercent = 80,
                rotationDegrees = 450,
            )
        )

        assertEquals(200, safe.saturationPercent)
        assertEquals(-50, safe.brightnessPercent)
        assertEquals(50, safe.contrastPercent)
        assertEquals(90, safe.rotationDegrees)
    }

    @Test
    fun detectsVisualChanges() {
        assertFalse(
            EditorVisualSettings.hasVisualChanges(
                EditorVisualSettings.Values()
            )
        )
        assertTrue(
            EditorVisualSettings.hasVisualChanges(
                EditorVisualSettings.Values(saturationPercent = 130)
            )
        )
        assertTrue(
            EditorVisualSettings.hasVisualChanges(
                EditorVisualSettings.Values(rotationDegrees = 90)
            )
        )
    }

    @Test
    fun invalidRotationFallsBackToZero() {
        val safe = EditorVisualSettings.sanitize(
            EditorVisualSettings.Values(rotationDegrees = 45)
        )

        assertEquals(0, safe.rotationDegrees)
    }
}
