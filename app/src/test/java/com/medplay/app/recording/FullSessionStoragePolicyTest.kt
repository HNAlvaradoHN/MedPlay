package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class FullSessionStoragePolicyTest {

    @Test
    fun reservesStorageAndCapsAtTwoHours() {
        val result = FullSessionStoragePolicy.choose(
            videoBitrate = 20_000_000,
            audioBitrate = 128_000,
            availableBytes = 40L * 1024L * 1024L * 1024L,
        )

        assertEquals(
            FullSessionStoragePolicy.MAX_SESSION_SECONDS,
            result.maxDurationSeconds,
        )
    }

    @Test
    fun refusesWhenOneMinuteCannotFitSafely() {
        val result = FullSessionStoragePolicy.choose(
            videoBitrate = 40_000_000,
            audioBitrate = 224_000,
            availableBytes =
                FullSessionStoragePolicy.STORAGE_RESERVE_BYTES +
                    20L * 1024L * 1024L,
        )

        assertEquals(0, result.maxDurationSeconds)
        assertEquals(0L, result.estimatedBytes)
    }

    @Test
    fun returnsAffordableDurationAboveReserve() {
        val bytesPerSecond = 1_000_000L
        val available =
            FullSessionStoragePolicy.STORAGE_RESERVE_BYTES +
                bytesPerSecond * 600L

        val result = FullSessionStoragePolicy.choose(
            videoBitrate = 8_000_000,
            audioBitrate = 0,
            availableBytes = available,
        )

        assertEquals(600, result.maxDurationSeconds)
        assertEquals(bytesPerSecond * 600L, result.estimatedBytes)
    }
}
