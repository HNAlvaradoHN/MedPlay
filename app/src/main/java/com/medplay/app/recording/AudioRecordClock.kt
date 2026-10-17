package com.medplay.app.recording

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock

/**
 * Establishes the real monotonic start of an AudioRecord from the HAL-reported
 * frame position + BOOTTIME clock when available.
 *
 * Encoded AAC timestamps remain sample-count based (the media timeline is
 * defined by the number of audio samples). The hardware timestamp is used to
 * anchor that relative timeline to the same monotonic clock as video and the
 * other audio source, avoiding scheduler-jitter-based start estimates.
 *
 * Some devices temporarily cannot provide a HAL timestamp immediately after
 * recording starts. In that case the fallback estimates the first captured
 * frame from the completion time of the first blocking read minus the duration
 * of the frames returned by that read. Using raw "now" here would shift sources
 * differently when their first read sizes differ (for example stereo playback
 * versus 10 ms mono microphone frames).
 */
object AudioRecordClock {

    fun captureStartMonotonicUs(
        record: AudioRecord,
        sampleRate: Int,
        framesReadAtQuery: Long,
    ): Long {
        require(sampleRate > 0)
        require(framesReadAtQuery >= 0L)
        val timestamp = AudioTimestamp()
        val result = runCatching {
            record.getTimestamp(
                timestamp,
                AudioTimestamp.TIMEBASE_BOOTTIME,
            )
        }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)

        if (result == AudioRecord.SUCCESS) {
            val frameOffsetUs =
                timestamp.framePosition * 1_000_000L / sampleRate
            return timestamp.nanoTime / 1_000L - frameOffsetUs
        }

        return estimateStartFromReadCompletionUs(
            readCompletedUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
            framesRead = framesReadAtQuery,
            sampleRate = sampleRate,
        )
    }

    internal fun estimateStartFromReadCompletionUs(
        readCompletedUs: Long,
        framesRead: Long,
        sampleRate: Int,
    ): Long {
        require(sampleRate > 0)
        require(framesRead >= 0L)

        val capturedDurationUs = framesRead * 1_000_000L / sampleRate
        return readCompletedUs - capturedDurationUs
    }
}
