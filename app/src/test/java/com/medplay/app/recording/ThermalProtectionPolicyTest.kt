package com.medplay.app.recording

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalProtectionPolicyTest {

    @Test
    fun keepsFullBitrateAtLightThermalState() {
        val action = ThermalProtectionPolicy.actionFor(PowerManager.THERMAL_STATUS_LIGHT)

        assertEquals(100, action.bitratePercent)
        assertFalse(action.stopCapture)
        assertFalse(action.requestProfileStepDown)
    }

    @Test
    fun reducesBitrateAtModerateAndStepsProfileAtSevere() {
        val moderate = ThermalProtectionPolicy.actionFor(PowerManager.THERMAL_STATUS_MODERATE)
        val severe = ThermalProtectionPolicy.actionFor(PowerManager.THERMAL_STATUS_SEVERE)

        assertEquals(85, moderate.bitratePercent)
        assertEquals(70, severe.bitratePercent)
        assertFalse(moderate.stopCapture)
        assertFalse(moderate.requestProfileStepDown)
        assertFalse(severe.stopCapture)
        assertTrue(severe.requestProfileStepDown)
    }

    @Test
    fun stopsAtCriticalThermalState() {
        val action = ThermalProtectionPolicy.actionFor(PowerManager.THERMAL_STATUS_CRITICAL)

        assertTrue(action.stopCapture)
        assertFalse(action.requestProfileStepDown)
    }
}
