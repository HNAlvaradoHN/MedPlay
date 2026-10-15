package com.medplay.app.recording

import java.util.concurrent.atomic.AtomicLong

class EncoderRuntimeHealth {

    data class Snapshot(
        val encodedSamples: Long,
        val timestampRegressions: Long,
        val maxWallOutputGapUs: Long,
        val timeSinceLastOutputUs: Long,
    )

    private val encodedSamples = AtomicLong(0)
    private val timestampRegressions = AtomicLong(0)
    private val maxWallOutputGapUs = AtomicLong(0)
    private val lastWallOutputUs = AtomicLong(Long.MIN_VALUE)
    private val lastPresentationUs = AtomicLong(Long.MIN_VALUE)

    fun onEncodedSample(
        presentationTimeUs: Long,
        wallTimeUs: Long,
    ) {
        encodedSamples.incrementAndGet()

        val previousPts = lastPresentationUs.getAndSet(presentationTimeUs)
        if (previousPts != Long.MIN_VALUE && presentationTimeUs < previousPts) {
            timestampRegressions.incrementAndGet()
        }

        val previousWall = lastWallOutputUs.getAndSet(wallTimeUs)
        if (previousWall != Long.MIN_VALUE) {
            val gap = (wallTimeUs - previousWall).coerceAtLeast(0L)
            updateMax(maxWallOutputGapUs, gap)
        }
    }

    fun snapshot(nowUs: Long): Snapshot {
        val last = lastWallOutputUs.get()
        return Snapshot(
            encodedSamples = encodedSamples.get(),
            timestampRegressions = timestampRegressions.get(),
            maxWallOutputGapUs = maxWallOutputGapUs.get(),
            timeSinceLastOutputUs = if (last == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                (nowUs - last).coerceAtLeast(0L)
            },
        )
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current) return
            if (target.compareAndSet(current, value)) return
        }
    }
}
