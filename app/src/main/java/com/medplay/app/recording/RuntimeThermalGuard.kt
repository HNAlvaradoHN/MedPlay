package com.medplay.app.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeThermalGuard(
    context: Context,
    private val onAction: (ThermalProtectionPolicy.Action, Int) -> Unit,
) : AutoCloseable {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private val started = AtomicBoolean(false)
    private var lastStatus: Int? = null

    private val severeRecheck = object : Runnable {
        override fun run() {
            if (!started.get()) return
            val status = powerManager.currentThermalStatus
            if (
                status >= PowerManager.THERMAL_STATUS_SEVERE &&
                status < PowerManager.THERMAL_STATUS_CRITICAL
            ) {
                dispatch(status, force = true)
            }
        }
    }

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        dispatch(status)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        powerManager.addThermalStatusListener(listener)
        dispatch(powerManager.currentThermalStatus)
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) return
        handler.removeCallbacks(severeRecheck)
        powerManager.removeThermalStatusListener(listener)
    }

    private fun dispatch(status: Int, force: Boolean = false) {
        if (!started.get()) return
        if (!force && lastStatus == status) return

        lastStatus = status
        handler.removeCallbacks(severeRecheck)
        onAction(ThermalProtectionPolicy.actionFor(status), status)

        if (
            status >= PowerManager.THERMAL_STATUS_SEVERE &&
            status < PowerManager.THERMAL_STATUS_CRITICAL
        ) {
            handler.postDelayed(severeRecheck, SEVERE_RECHECK_MS)
        }
    }

    companion object {
        private const val SEVERE_RECHECK_MS = 30_000L
    }
}
