package com.medplay.app.recording

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock

/**
 * Maps AudioRecord frame positions onto CLOCK_BOOTTIME.
 *
 * The fallback remains frame-count based until the HAL exposes a timestamp.
 * Once a hardware timestamp is available, every block start is derived from
 * that frame-position/time anchor instead of from the Kotlin thread wake-up
 * time. This keeps the capture clock stable without adding scheduler jitter.
 */
internal class AudioRecordFrameClock(
    private val sampleRate: Int,
) {
    data class BlockTime(
        val absoluteStartUs: Long,
        val relativePtsUs: Long,
    )

    private val timestamp = AudioTimestamp()
    private val fallbackStartUs =
        SystemClock.elapsedRealtimeNanos() / 1_000L

    private var nextFramePosition = 0L
    private var firstAbsoluteStartUs: Long? = null
    private var lastRelativePtsUs = Long.MIN_VALUE

    fun timestampBlock(
        record: AudioRecord,
        frameCount: Int,
    ): BlockTime {
        val blockStartFrame = nextFramePosition
        nextFramePosition += frameCount.toLong()

        val hardwareTimeUs = runCatching {
            val status = record.getTimestamp(
                timestamp,
                AudioTimestamp.TIMEBASE_BOOTTIME,
            )
            if (status == AudioRecord.SUCCESS) {
                val frameDelta =
                    blockStartFrame - timestamp.framePosition
                timestamp.nanoTime / 1_000L +
                    frameDelta * 1_000_000L / sampleRate
            } else {
                null
            }
        }.getOrNull()

        val absoluteStartUs = hardwareTimeUs ?: (
            fallbackStartUs +
                blockStartFrame * 1_000_000L / sampleRate
            )

        val origin = firstAbsoluteStartUs ?: absoluteStartUs.also {
            firstAbsoluteStartUs = it
        }
        var relativePtsUs = (absoluteStartUs - origin).coerceAtLeast(0L)

        // Defensive monotonicity only. AudioTimestamp should already be stable,
        // but a vendor HAL must never be allowed to regress codec PTS.
        if (lastRelativePtsUs != Long.MIN_VALUE) {
            relativePtsUs = maxOf(relativePtsUs, lastRelativePtsUs)
        }
        lastRelativePtsUs = relativePtsUs

        return BlockTime(
            absoluteStartUs = absoluteStartUs,
            relativePtsUs = relativePtsUs,
        )
    }
}
