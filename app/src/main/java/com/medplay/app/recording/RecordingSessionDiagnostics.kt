package com.medplay.app.recording

data class RecordingSessionDiagnostics(
    val startedMonotonicUs: Long,
    val endedMonotonicUs: Long,
    val profile: RecordingProfile,
    val retentionSeconds: Int,
    val maxThermalStatus: Int,
    val audioWasActive: Boolean,
    val encoderHealth: EncoderRuntimeHealth.Snapshot,
) {
    val durationUs: Long
        get() = (endedMonotonicUs - startedMonotonicUs).coerceAtLeast(0L)

    val sustainabilityVerdict: RecordingSustainabilityEvaluator.Verdict
        get() = RecordingSustainabilityEvaluator.evaluate(
            RecordingSustainabilityEvaluator.Input(
                encoderHealth = encoderHealth,
                thermalStatus = maxThermalStatus,
                targetFps = profile.fps,
            )
        )

    fun toText(): String {
        return buildString {
            appendLine("MedPlay recording diagnostics")
            appendLine("durationUs=$durationUs")
            appendLine("resolution=${profile.width}x${profile.height}")
            appendLine("fps=${profile.fps}")
            appendLine("bitrate=${profile.bitrate}")
            appendLine("codec=${profile.codec.mimeType}")
            appendLine("encoder=${profile.encoderName}")
            appendLine("retentionSeconds=$retentionSeconds")
            appendLine("audioWasActive=$audioWasActive")
            appendLine("maxThermalStatus=$maxThermalStatus")
            appendLine("sustainabilityVerdict=$sustainabilityVerdict")
            appendLine("encodedSamples=${encoderHealth.encodedSamples}")
            appendLine("timestampRegressions=${encoderHealth.timestampRegressions}")
            appendLine("maxWallOutputGapUs=${encoderHealth.maxWallOutputGapUs}")
            appendLine("timeSinceLastOutputUs=${encoderHealth.timeSinceLastOutputUs}")
        }
    }
}
