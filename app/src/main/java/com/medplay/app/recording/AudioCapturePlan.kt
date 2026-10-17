package com.medplay.app.recording

/**
 * Decides whether playback capture is persisted or used only as the clean
 * far-end reference for microphone echo cancellation.
 */
object AudioCapturePlan {

    data class Plan(
        val capturePlayback: Boolean,
        val recordInternalAudio: Boolean,
        val referenceOnly: Boolean,
    )

    fun decide(
        internalAudioEnabled: Boolean,
        softwareAecRequested: Boolean,
    ): Plan {
        val capturePlayback =
            internalAudioEnabled || softwareAecRequested
        return Plan(
            capturePlayback = capturePlayback,
            recordInternalAudio = internalAudioEnabled,
            referenceOnly =
                softwareAecRequested && !internalAudioEnabled,
        )
    }
}
