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
 */
object AudioRecordClock {

    fun captureStartMonotonicUs(
        record: AudioRecord,
        sampleRate: Int,
    ): Long {
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

        return SystemClock.elapsedRealtimeNanos() / 1_000L
    }
}
