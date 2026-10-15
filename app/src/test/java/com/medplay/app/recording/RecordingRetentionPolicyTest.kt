package com.medplay.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRetentionPolicyTest {

    @Test
    fun allowsFiveMinutesWhenStorageBudgetSupportsIt() {
        val result = RecordingRetentionPolicy.choose(
            videoBitrate = 12_000_000,
            audioBitrate = 128_000,
            usableBytes = 3L * 1024L * 1024L * 1024L,
        )

        assertEquals(300, result.retentionSeconds)
        assertTrue(result.estimatedBytes > 0)
    }

    @Test
    fun reducesRetentionWhenStorageIsTight() {
        val result = RecordingRetentionPolicy.choose(
            videoBitrate = 16_000_000,
            audioBitrate = 128_000,
            usableBytes = 950L * 1024L * 1024L,
        )

        assertTrue(result.retentionSeconds in 60 until 300)
    }

    @Test
    fun neverDropsBelowOneMinute() {
        val result = RecordingRetentionPolicy.choose(
            videoBitrate = 16_000_000,
            audioBitrate = 128_000,
            usableBytes = 100L * 1024L * 1024L,
        )

        assertEquals(60, result.retentionSeconds)
    }
}
