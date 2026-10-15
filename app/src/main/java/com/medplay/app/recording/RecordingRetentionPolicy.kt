package com.medplay.app.recording

import kotlin.math.floor

object RecordingRetentionPolicy {

    data class Result(
        val retentionSeconds: Int,
        val estimatedBytes: Long,
    )

    fun choose(
        videoBitrate: Int,
        audioBitrate: Int,
        usableBytes: Long,
        requestedSeconds: Int = MAX_RETENTION_SECONDS,
    ): Result {
        require(videoBitrate > 0)
        require(audioBitrate >= 0)
        require(usableBytes >= 0)

        val boundedRequested = requestedSeconds.coerceIn(
            MIN_RETENTION_SECONDS,
            MAX_RETENTION_SECONDS,
        )

        val bytesPerSecond = (videoBitrate.toLong() + audioBitrate) / 8L
        val safeBudget = (usableBytes - STORAGE_RESERVE_BYTES)
            .coerceAtLeast(0L)
            .coerceAtMost(MAX_ROLLING_STORAGE_BYTES)

        val affordableSeconds = if (bytesPerSecond <= 0L) {
            boundedRequested
        } else {
            floor(safeBudget.toDouble() / bytesPerSecond.toDouble())
                .toInt()
        }

        if (affordableSeconds < MIN_RETENTION_SECONDS) {
            return Result(
                retentionSeconds = 0,
                estimatedBytes = 0L,
            )
        }

        val selected = affordableSeconds.coerceAtMost(boundedRequested)

        return Result(
            retentionSeconds = selected,
            estimatedBytes = bytesPerSecond * selected,
        )
    }

    const val MIN_RETENTION_SECONDS = 60
    const val MAX_RETENTION_SECONDS = 300

    private const val STORAGE_RESERVE_BYTES = 768L * 1024L * 1024L
    private const val MAX_ROLLING_STORAGE_BYTES = 768L * 1024L * 1024L
}
