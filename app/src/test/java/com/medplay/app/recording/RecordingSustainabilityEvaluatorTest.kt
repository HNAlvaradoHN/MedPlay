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

    @Test
    fun goodWhenEncoderAndThermalsAreHealthy() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(),
                thermalStatus = PowerManager.THERMAL_STATUS_LIGHT,
                targetFps = 30,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.GOOD, verdict)
    }

    @Test
    fun degradedOnModerateThermalStatus() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(),
                thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
                targetFps = 30,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.DEGRADED, verdict)
    }

    @Test
    fun degradedOnLargeOutputGap() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(maxGapUs = 150_000),
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                targetFps = 30,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.DEGRADED, verdict)
    }

    @Test
    fun unsustainableOnTimestampRegression() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(regressions = 1),
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                targetFps = 60,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE, verdict)
    }

    @Test
    fun unsustainableOnEncoderStall() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(sinceLastUs = 1_100_000),
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                targetFps = 30,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE, verdict)
    }

    @Test
    fun unsustainableOnCriticalThermalStatus() {
        val verdict = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = health(),
                thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL,
                targetFps = 30,
            )
        )

        assertEquals(RecordingSustainabilityEvaluator.Verdict.UNSUSTAINABLE, verdict)
    }
}
