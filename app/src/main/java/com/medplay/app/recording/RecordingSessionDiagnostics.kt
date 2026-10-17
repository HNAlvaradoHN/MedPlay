package com.medplay.app.recording

data class RecordingSessionDiagnostics(
    val startedMonotonicUs: Long,
    val endedMonotonicUs: Long,
    val profile: RecordingProfile,
    val retentionSeconds: Int,
    val maxThermalStatus: Int,
    val audioWasActive: Boolean,
    val encoderHealth: EncoderRuntimeHealth.Snapshot,
    val microphoneWasActive: Boolean = false,
    val microphoneWasSilenced: Boolean = false,
    val microphonePeakAbs: Int = 0,
    val microphoneAecAvailable: Boolean = false,
    val microphoneAecEnabled: Boolean = false,
    val microphoneAecMode: String = "none",
    val microphoneAecRenderFrames: Long = 0L,
    val microphoneAecCaptureFrames: Long = 0L,
    val microphoneAecResets: Int = 0,
    val microphoneAecEchoReturnLossDb: Double = 0.0,
    val microphoneAecEchoReturnLossEnhancementDb: Double = 0.0,
    val microphoneAecEstimatedDelayMs: Int = 0,
    val thermalProfileDowngrades: Int = 0,
    val initialProfile: RecordingProfile? = null,
) {
    val durationUs: Long
        get() = (endedMonotonicUs - startedMonotonicUs).coerceAtLeast(0L)

    val sustainabilityVerdict: RecordingSustainabilityEvaluator.Verdict
        get() = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = encoderHealth,
                thermalStatus = maxThermalStatus,
                targetFps = profile.fps,
                sessionDurationUs = durationUs,
            )
        )

    fun toText(): String {
        return buildString {
            appendLine("MedPlay recording diagnostics")
            appendLine("durationUs=$durationUs")
            val startedProfile = initialProfile ?: profile
            appendLine("resolution=${profile.width}x${profile.height}")
            appendLine("fps=${profile.fps}")
            appendLine("initialResolution=${startedProfile.width}x${startedProfile.height}")
            appendLine("initialFps=${startedProfile.fps}")
            appendLine("thermalProfileDowngrades=$thermalProfileDowngrades")
            appendLine("bitrate=${profile.bitrate}")
            appendLine("codec=${profile.codec.mimeType}")
            appendLine("encoder=${profile.encoderName}")
            appendLine("retentionSeconds=$retentionSeconds")
            appendLine("audioWasActive=$audioWasActive")
            appendLine("microphoneWasActive=$microphoneWasActive")
            appendLine("microphoneWasSilenced=$microphoneWasSilenced")
            appendLine("microphonePeakAbs=$microphonePeakAbs")
            appendLine("microphoneAecAvailable=$microphoneAecAvailable")
            appendLine("microphoneAecEnabled=$microphoneAecEnabled")
            appendLine("microphoneAecMode=$microphoneAecMode")
            appendLine("microphoneAecRenderFrames=$microphoneAecRenderFrames")
            appendLine("microphoneAecCaptureFrames=$microphoneAecCaptureFrames")
            appendLine("microphoneAecResets=$microphoneAecResets")
            appendLine(
                "microphoneAecEchoReturnLossDb=" +
                    microphoneAecEchoReturnLossDb
            )
            appendLine(
                "microphoneAecEchoReturnLossEnhancementDb=" +
                    microphoneAecEchoReturnLossEnhancementDb
            )
            appendLine(
                "microphoneAecEstimatedDelayMs=" +
                    microphoneAecEstimatedDelayMs
            )
            appendLine("maxThermalStatus=$maxThermalStatus")
            appendLine("sustainabilityVerdict=$sustainabilityVerdict")
            appendLine("encodedSamples=${encoderHealth.encodedSamples}")
            appendLine("timestampRegressions=${encoderHealth.timestampRegressions}")
            appendLine("maxWallOutputGapUs=${encoderHealth.maxWallOutputGapUs}")
            appendLine("timeSinceLastOutputUs=${encoderHealth.timeSinceLastOutputUs}")
        }
    }
}
