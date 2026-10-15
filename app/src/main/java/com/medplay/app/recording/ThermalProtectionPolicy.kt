package com.medplay.app.recording

import android.os.PowerManager

object ThermalProtectionPolicy {

    data class Action(
        val bitratePercent: Int,
        val stopCapture: Boolean,
        val requestProfileStepDown: Boolean,
    )

    fun actionFor(status: Int): Action {
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> Action(
                bitratePercent = 0,
                stopCapture = true,
                requestProfileStepDown = false,
            )
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Action(
                bitratePercent = 70,
                stopCapture = false,
                requestProfileStepDown = true,
            )
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Action(
                bitratePercent = 85,
                stopCapture = false,
                requestProfileStepDown = false,
            )
            else -> Action(
                bitratePercent = 100,
                stopCapture = false,
                requestProfileStepDown = false,
            )
        }
    }
}
