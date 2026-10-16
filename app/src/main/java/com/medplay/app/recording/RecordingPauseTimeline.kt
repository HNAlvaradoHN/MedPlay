package com.medplay.app.recording

data class RecordingPauseInterval(
    val startUs: Long,
    val endUs: Long,
) {
    init {
        require(endUs >= startUs)
    }
}

object RecordingPauseTimeline {

    fun outputTimeUs(
        absoluteUs: Long,
        clipStartUs: Long,
        pauses: List<RecordingPauseInterval>,
    ): Long {
        require(absoluteUs >= clipStartUs)

        var removedUs = 0L
        for (pause in pauses) {
            if (pause.endUs <= clipStartUs) continue
            if (pause.startUs >= absoluteUs) break

            val overlapStart = maxOf(pause.startUs, clipStartUs)
            val overlapEnd = minOf(pause.endUs, absoluteUs)
            if (overlapEnd > overlapStart) {
                removedUs += overlapEnd - overlapStart
            }
        }

        return (absoluteUs - clipStartUs - removedUs).coerceAtLeast(0L)
    }

    fun removedDurationUs(
        clipStartUs: Long,
        clipEndUs: Long,
        pauses: List<RecordingPauseInterval>,
    ): Long {
        if (clipEndUs <= clipStartUs) return 0L

        var total = 0L
        for (pause in pauses) {
            val overlapStart = maxOf(pause.startUs, clipStartUs)
            val overlapEnd = minOf(pause.endUs, clipEndUs)
            if (overlapEnd > overlapStart) {
                total += overlapEnd - overlapStart
            }
        }
        return total
    }
}
