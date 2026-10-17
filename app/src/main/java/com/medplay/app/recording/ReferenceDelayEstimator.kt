package com.medplay.app.recording

import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * Estimates the render -> microphone delay from the raw signals themselves.
 *
 * AudioPlaybackCapture and the microphone have independent buffering. Their
 * timestamps tell us when each recorder produced a frame, but they do not tell
 * us how long it took a playback frame to reach the loudspeaker and then the
 * microphone. AEC3's external audio-buffer delay therefore must not be derived
 * from the latest render/capture timestamp skew alone.
 *
 * This estimator compares 10 ms energy envelopes from clean playback and raw
 * microphone capture. Near-end speech behaves mostly as uncorrelated energy,
 * while loudspeaker leakage preserves the far-end envelope at a delayed time.
 */
class ReferenceDelayEstimator(
    private val minDelayMs: Int = 0,
    private val maxDelayMs: Int = 250,
    private val stepMs: Int = 10,
    private val windowFrames: Int = 100,
    private val updateEveryCaptureFrames: Int = 50,
    private val minimumPairs: Int = 30,
    private val minimumCorrelation: Double = 0.30,
) {

    data class Estimate(
        val delayMs: Int,
        val correlation: Double,
    )

    private data class Point(
        val startUs: Long,
        val energy: Double,
    )

    private val render = ArrayDeque<Point>()
    private val capture = ArrayDeque<Point>()
    private var capturesSinceUpdate = 0

    fun reset() {
        render.clear()
        capture.clear()
        capturesSinceUpdate = 0
    }

    fun offerRender(startUs: Long, samples: FloatArray) {
        render.addLast(Point(startUs, energy(samples)))
        trim(render)
    }

    fun offerCapture(startUs: Long, samples: FloatArray): Estimate? {
        capture.addLast(Point(startUs, energy(samples)))
        trim(capture)

        capturesSinceUpdate++
        if (capturesSinceUpdate < updateEveryCaptureFrames) return null
        capturesSinceUpdate = 0

        if (capture.size < minimumPairs || render.size < minimumPairs) return null

        val renderList = render.toList()
        val captureList = capture.toList()

        var bestDelayMs = -1
        var bestCorrelation = Double.NEGATIVE_INFINITY

        var delayMs = minDelayMs
        while (delayMs <= maxDelayMs) {
            val correlation = correlationAtDelay(
                render = renderList,
                capture = captureList,
                delayUs = delayMs * 1_000L,
            )
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestDelayMs = delayMs
            }
            delayMs += stepMs
        }

        if (
            bestDelayMs < 0 ||
            !bestCorrelation.isFinite() ||
            bestCorrelation < minimumCorrelation
        ) {
            return null
        }

        return Estimate(bestDelayMs, bestCorrelation)
    }

    private fun correlationAtDelay(
        render: List<Point>,
        capture: List<Point>,
        delayUs: Long,
    ): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()

        capture.forEach { capturePoint ->
            val targetRenderUs = capturePoint.startUs - delayUs
            val renderPoint = nearestRender(render, targetRenderUs) ?: return@forEach
            if (kotlin.math.abs(renderPoint.startUs - targetRenderUs) > MATCH_TOLERANCE_US) {
                return@forEach
            }
            xs += renderPoint.energy
            ys += capturePoint.energy
        }

        if (xs.size < minimumPairs) return Double.NEGATIVE_INFINITY

        val meanX = xs.average()
        val meanY = ys.average()
        var numerator = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0

        for (index in xs.indices) {
            val dx = xs[index] - meanX
            val dy = ys[index] - meanY
            numerator += dx * dy
            sumX2 += dx * dx
            sumY2 += dy * dy
        }

        val denominator = sqrt(sumX2 * sumY2)
        if (denominator <= MIN_VARIANCE) return Double.NEGATIVE_INFINITY
        return numerator / denominator
    }

    private fun nearestRender(
        points: List<Point>,
        targetUs: Long,
    ): Point? {
        if (points.isEmpty()) return null

        var low = 0
        var high = points.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val timeUs = points[mid].startUs
            when {
                timeUs < targetUs -> low = mid + 1
                timeUs > targetUs -> high = mid - 1
                else -> return points[mid]
            }
        }

        val before = points.getOrNull(high)
        val after = points.getOrNull(low)
        return when {
            before == null -> after
            after == null -> before
            kotlin.math.abs(before.startUs - targetUs) <=
                kotlin.math.abs(after.startUs - targetUs) -> before
            else -> after
        }
    }

    private fun energy(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0

        var sumAbs = 0.0
        for (sample in samples) {
            sumAbs += kotlin.math.abs(sample.toDouble())
        }
        // Log compression keeps occasional loud transients from dominating the
        // whole correlation window while preserving the 10 ms energy envelope.
        return ln1p(sumAbs / samples.size)
    }

    private fun trim(queue: ArrayDeque<Point>) {
        while (queue.size > windowFrames) {
            queue.removeFirst()
        }
    }

    companion object {
        private const val MATCH_TOLERANCE_US = 6_000L
        private const val MIN_VARIANCE = 1e-9
    }
}
