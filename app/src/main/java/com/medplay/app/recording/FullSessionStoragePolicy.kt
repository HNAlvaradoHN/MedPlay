package com.medplay.app.recording

import kotlin.math.floor

object FullSessionStoragePolicy {

    data class Result(
        val maxDurationSeconds: Int,
        val estimatedBytes: Long,
    )

    fun choose(
        videoBitrate: Int,
        audioBitrate: Int,
        availableBytes: Long,
    ): Result {
        require(videoBitrate > 0)
        require(audioBitrate >= 0)
        require(availableBytes >= 0)

        val bytesPerSecond = (videoBitrate.toLong() + audioBitrate) / 8L
        val budget = (availableBytes - STORAGE_RESERVE_BYTES).coerceAtLeast(0L)
        val affordableSeconds = if (bytesPerSecond <= 0L) {
            MAX_SESSION_SECONDS
        } else {
            floor(budget.toDouble() / bytesPerSecond.toDouble()).toInt()
        }

        if (affordableSeconds < MIN_SESSION_SECONDS) {
            return Result(0, 0L)
        }

        val selected = affordableSeconds.coerceAtMost(MAX_SESSION_SECONDS)
        return Result(
            maxDurationSeconds = selected,
            estimatedBytes = bytesPerSecond * selected,
        )
    }

    const val MIN_SESSION_SECONDS = 60
    const val MAX_SESSION_SECONDS = 7_200
    const val STORAGE_RESERVE_BYTES = 768L * 1024L * 1024L
}
