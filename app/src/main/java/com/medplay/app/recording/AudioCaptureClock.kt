package com.medplay.app.recording

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock

/**
 * Maps AudioRecord frame positions onto CLOCK_BOOTTIME so audio PTS follows
 * the hardware capture clock instead of assuming an ideal 48 kHz oscillator.
 */
class AudioCaptureClock(
    private val sampleRate: Int,
    private val captureStartUs: Long,
) {
    data class Timing(
        val absoluteStartUs: Long,
        val presentationTimeUs: Long,
        val hardwareTimestamp: Boolean,
    )

    private val timestamp = AudioTimestamp()
    private var lastPtsUs = 0L

    fun timingForRead(
        record: AudioRecord,
        firstFramePosition: Long,
        framesRead: Int,
    ): Timing {
        val hardware = runCatching {
            record.getTimestamp(
                timestamp,
                AudioTimestamp.TIMEBASE_BOOTTIME,
            ) == AudioRecord.SUCCESS
        }.getOrDefault(false)

        val absoluteStartUs = if (hardware) {
            val timestampUs = timestamp.nanoTime / 1_000L
            val deltaFrames = firstFramePosition - timestamp.framePosition
            timestampUs + framesToUs(deltaFrames)
        } else {
            val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            nowUs - framesToUs(framesRead.toLong())
        }

        val candidatePtsUs = (absoluteStartUs - captureStartUs).coerceAtLeast(0L)
        val monotonicPtsUs = candidatePtsUs.coerceAtLeast(lastPtsUs)
        lastPtsUs = monotonicPtsUs

        return Timing(
            absoluteStartUs = captureStartUs + monotonicPtsUs,
            presentationTimeUs = monotonicPtsUs,
            hardwareTimestamp = hardware,
        )
    }

    fun ptsForFramePosition(framePosition: Long): Long {
        return framesToUs(framePosition).coerceAtLeast(lastPtsUs)
    }

    private fun framesToUs(frames: Long): Long {
        return frames * 1_000_000L / sampleRate
    }
}
