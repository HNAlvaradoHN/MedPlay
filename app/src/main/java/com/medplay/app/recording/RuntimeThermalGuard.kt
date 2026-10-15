package com.medplay.app.recording

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeThermalGuard(
    context: Context,
    private val onAction: (ThermalProtectionPolicy.Action, Int) -> Unit,
) : AutoCloseable {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val started = AtomicBoolean(false)
    private var lastStatus: Int? = null

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
        powerManager.removeThermalStatusListener(listener)
    }

    private fun dispatch(status: Int) {
        if (!started.get()) return
        if (lastStatus == status) return
        lastStatus = status
        onAction(ThermalProtectionPolicy.actionFor(status), status)
    }
}
