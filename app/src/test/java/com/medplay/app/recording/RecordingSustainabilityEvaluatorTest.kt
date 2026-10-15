package com.medplay.app.recording

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSustainabilityEvaluatorTest {

    private fun health(
        samples: Long = 100,
        regressions: Long = 0,
        maxGapUs: Long = 30_000,
        sinceLastUs: Long = 10_000,
    ) = EncoderRuntimeHealth.Snapshot(
        encodedSamples = samples,
        timestampRegressions = regressions,
        maxWallOutputGapUs = maxGapUs,
        timeSinceLastOutputUs = sinceLastUs,
    )

    private fun input(
        health: EncoderRuntimeHealth.Snapshot = health(),
        thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        targetFps: Int = 30,
        durationUs: Long = 10_000_000L,
    ) = RecordingSustainabilityEvaluator.Input(
        encoderHealth = health,
        thermalStatus = thermalStatus,
        targetFps = targetFps,
        sessionDurationUs = durationUs,
    )

    @Test
    fun goodWhenEncoderAndThermalsAreHealthy() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.GOOD,
            RecordingSustainabilityEvaluator.evaluate(
                input(thermalStatus = PowerManager.THERMAL_STATUS_LIGHT)
            ),
        )
    }

    @Test
    fun degradedOnModerateThermalStatus() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.DEGRADED,
            RecordingSustainabilityEvaluator.evaluate(
                input(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE)
            ),
        )
    }

    @Test
    fun degradedOnLargeOutputGap() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.DEGRADED,
            RecordingSustainabilityEvaluator.evaluate(
                input(health = health(maxGapUs = 150_000))
            ),
        )
    }

    @Test
    fun unsustainableOnTimestampRegression() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE,
            RecordingSustainabilityEvaluator.evaluate(
                input(health = health(regressions = 1), targetFps = 60)
            ),
        )
    }

    @Test
    fun unsustainableOnEncoderStall() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE,
            RecordingSustainabilityEvaluator.evaluate(
                input(health = health(sinceLastUs = 1_100_000))
            ),
        )
    }

    @Test
    fun unsustainableOnCriticalThermalStatus() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE,
            RecordingSustainabilityEvaluator.evaluate(
                input(thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL)
            ),
        )
    }

    @Test
    fun zeroSamplesBecomeUnsustainableAfterStartupGrace() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE,
            RecordingSustainabilityEvaluator.evaluate(
                input(
                    health = health(samples = 0),
                    durationUs = 3_000_000L,
                )
            ),
        )
    }

    @Test
    fun veryShortZeroSampleSessionIsDegradedNotGood() {
        assertEquals(
            RecordingSustainabilityEvaluator.Verdict.DEGRADED,
            RecordingSustainabilityEvaluator.evaluate(
                input(
                    health = health(samples = 0),
                    durationUs = 500_000L,
                )
            ),
        )
    }
}
