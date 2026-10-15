package com.medplay.app.recording

import android.os.PowerManager

object RecordingSustainabilityEvaluator {

    enum class Verdict {
        GOOD,
        DEGRADED,
        UNSUSTAINABLE,
    }

    data class Input(
        val encoderHealth: EncoderRuntimeHealth.Snapshot,
        val thermalStatus: Int,
        val targetFps: Int,
        val sessionDurationUs: Long,
    )

    fun evaluate(input: Input): Verdict {
        val frameIntervalUs = 1_000_000L / input.targetFps.coerceAtLeast(1)
        val health = input.encoderHealth

        if (
            input.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ||
            health.timestampRegressions > 0L ||
            (
                health.encodedSamples > 0L &&
                    health.timeSinceLastOutputUs > frameIntervalUs * STALL_FRAME_INTERVALS
                ) ||
            (
                input.sessionDurationUs >= ZERO_SAMPLE_UNSUSTAINABLE_AFTER_US &&
                    health.encodedSamples == 0L
                )
        ) {
            return Verdict.UNSUSTAINABLE
        }

        if (
            health.encodedSamples == 0L ||
            input.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            health.maxWallOutputGapUs > frameIntervalUs * DEGRADED_GAP_FRAME_INTERVALS
        ) {
            return Verdict.DEGRADED
        }

        return Verdict.GOOD
    }

    private const val DEGRADED_GAP_FRAME_INTERVALS = 4L
    private const val STALL_FRAME_INTERVALS = 30L
    private const val ZERO_SAMPLE_UNSUSTAINABLE_AFTER_US = 2_000_000L
}
