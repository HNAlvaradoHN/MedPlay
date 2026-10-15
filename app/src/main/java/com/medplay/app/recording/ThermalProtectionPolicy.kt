package com.medplay.app.recording

import android.os.PowerManager

object ThermalProtectionPolicy {

    data class Action(
        val bitratePercent: Int,
        val stopCapture: Boolean,
    )

    fun actionFor(status: Int): Action {
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> Action(
                bitratePercent = 0,
                stopCapture = true,
            )
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Action(
                bitratePercent = 70,
                stopCapture = false,
            )
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Action(
                bitratePercent = 85,
                stopCapture = false,
            )
            else -> Action(
                bitratePercent = 100,
                stopCapture = false,
            )
        }
    }
}
