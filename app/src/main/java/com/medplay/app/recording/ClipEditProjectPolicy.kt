package com.medplay.app.recording

object ClipEditProjectPolicy {

    data class Values(
        val trimStartUs: Long,
        val trimEndUs: Long,
        val gameAudioGainPercent: Int,
        val microphoneGainPercent: Int,
    )

    fun sanitize(
        trimStartUs: Long,
        trimEndUs: Long,
        sourceDurationUs: Long,
        gameAudioGainPercent: Int,
        microphoneGainPercent: Int,
    ): Values {
        val duration = sourceDurationUs.coerceAtLeast(0L)
        val start = trimStartUs.coerceIn(0L, duration)
        val end = trimEndUs.coerceIn(start, duration)
        return Values(
            trimStartUs = start,
            trimEndUs = end,
            gameAudioGainPercent = gameAudioGainPercent.coerceIn(0, 200),
            microphoneGainPercent = microphoneGainPercent.coerceIn(0, 200),
        )
    }
}
