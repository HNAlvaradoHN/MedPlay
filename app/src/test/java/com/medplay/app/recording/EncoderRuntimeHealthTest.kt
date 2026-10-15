package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderRuntimeHealthTest {

    @Test
    fun tracksOutputGapsAndSampleCount() {
        val health = EncoderRuntimeHealth()

        health.onEncodedSample(0L, 1_000_000L)
        health.onEncodedSample(33_333L, 1_040_000L)
        health.onEncodedSample(66_666L, 1_100_000L)

        val snapshot = health.snapshot(1_120_000L)

        assertEquals(3L, snapshot.encodedSamples)
        assertEquals(60_000L, snapshot.maxWallOutputGapUs)
        assertEquals(20_000L, snapshot.timeSinceLastOutputUs)
        assertEquals(0L, snapshot.timestampRegressions)
    }

    @Test
    fun detectsPresentationTimestampRegression() {
        val health = EncoderRuntimeHealth()

        health.onEncodedSample(100_000L, 1_000_000L)
        health.onEncodedSample(90_000L, 1_020_000L)

        assertEquals(1L, health.snapshot(1_030_000L).timestampRegressions)
    }

    @Test
    fun reportsNoOutputYet() {
        val health = EncoderRuntimeHealth()

        assertTrue(health.snapshot(1_000_000L).timeSinceLastOutputUs == Long.MAX_VALUE)
    }
}
