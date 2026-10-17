package com.medplay.app.recording

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ReferenceDelayEstimatorTest {

    @Test
    fun estimatesKnownRenderToCaptureDelay() {
        val estimator = ReferenceDelayEstimator(
            minDelayMs = 0,
            maxDelayMs = 200,
            stepMs = 10,
            windowFrames = 100,
            updateEveryCaptureFrames = 50,
            minimumPairs = 30,
            minimumCorrelation = 0.20,
        )

        val delayFrames = 8
        val renderFrames = ArrayList<FloatArray>()
        var estimate: ReferenceDelayEstimator.Estimate? = null

        repeat(120) { frame ->
            val render = frame(frame)
            renderFrames += render
            val renderStartUs = frame * 10_000L
            estimator.offerRender(renderStartUs, render)

            val capture = if (frame >= delayFrames) {
                val delayed = renderFrames[frame - delayFrames]
                FloatArray(delayed.size) { index ->
                    // Preserve the delayed far-end envelope while adding a
                    // smaller unrelated near-end component.
                    delayed[index] +
                        (sin((frame * delayed.size + index) * 0.071) * 1_000.0)
                            .toFloat()
                }
            } else {
                FloatArray(480)
            }

            val candidate = estimator.offerCapture(
                startUs = frame * 10_000L,
                samples = capture,
            )
            if (candidate != null) estimate = candidate
        }

        val measured = assertNotNull(estimate).let { estimate!! }
        assertTrue(
            "Expected delay near 80 ms, got ${measured.delayMs}",
            measured.delayMs in 70..90,
        )
        assertTrue(measured.correlation > 0.20)
    }

    @Test
    fun doesNotInventDelayForUncorrelatedSignals() {
        val estimator = ReferenceDelayEstimator(
            minimumCorrelation = 0.80,
        )

        var produced = false
        repeat(120) { frame ->
            estimator.offerRender(
                frame * 10_000L,
                FloatArray(480) { i ->
                    sin((frame * 480 + i) * 0.031).toFloat() * 8_000f
                },
            )
            val estimate = estimator.offerCapture(
                frame * 10_000L,
                FloatArray(480) { i ->
                    sin((frame * 480 + i) * 0.173 + 1.7).toFloat() * 8_000f
                },
            )
            produced = produced || estimate != null
        }

        assertTrue("Uncorrelated signals should not produce an estimate", !produced)
    }

    private fun frame(index: Int): FloatArray {
        // Vary the 10 ms frame energy so the envelope has enough structure for
        // a coarse delay estimate, similar to game audio with speech/effects.
        val envelope = 3_000f + ((index * 7919) % 20_000)
        return FloatArray(480) { sample ->
            (sin(2.0 * PI * sample / 41.0) * envelope).toFloat()
        }
    }
}
