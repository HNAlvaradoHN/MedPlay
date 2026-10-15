package com.medplay.app.recording

/**
 * High-motion recording policy.
 *
 * The targets are intentionally tuned for gameplay rather than static video:
 * enough bitrate must be available before a resolution/FPS mode is exposed.
 * Automatic mode prefers sharp 1080p with fluid motion before attempting 1440p,
 * because game responsiveness and sustained thermals remain release criteria.
 */
object SustainableProfilePolicy {

    data class SupportedProfile(
        val encoderName: String,
        val codec: VideoCodec,
        val hardwareAccelerated: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
    ) {
        fun toRecordingProfile() = RecordingProfile(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            codec = codec,
            encoderName = encoderName,
        )
    }

    data class Target(
        val codec: VideoCodec,
        val width: Int,
        val height: Int,
        val fps: Int,
        val preferredBitrate: Int,
        val minimumBitrate: Int,
        val automatic: Boolean,
    )

    /**
     * Practical high-motion candidates. A device only exposes a candidate when
     * the hardware encoder validates size/rate and has enough bitrate headroom.
     *
     * Automatic mode intentionally tops out at 1080p60 for now. 1440p modes are
     * available manually when validated by hardware, while real-device evidence
     * is gathered before making that extra load an automatic default.
     */
    val qualityTargets: List<Target> = listOf(
        // 1080p60: preferred automatic target for fast-motion games.
        Target(VideoCodec.HEVC, 1920, 1080, 60, 40_000_000, 28_000_000, true),
        Target(VideoCodec.AVC, 1920, 1080, 60, 55_000_000, 38_000_000, true),

        // 1080p30: prioritize per-frame detail if 60 FPS is not sustainable.
        Target(VideoCodec.HEVC, 1920, 1080, 30, 24_000_000, 18_000_000, true),
        Target(VideoCodec.AVC, 1920, 1080, 30, 34_000_000, 24_000_000, true),

        // 720p60 remains preferable to 720p30 for shooters when bandwidth allows.
        Target(VideoCodec.HEVC, 1280, 720, 60, 20_000_000, 14_000_000, true),
        Target(VideoCodec.AVC, 1280, 720, 60, 28_000_000, 20_000_000, true),
        Target(VideoCodec.HEVC, 1280, 720, 30, 12_000_000, 9_000_000, true),
        Target(VideoCodec.AVC, 1280, 720, 30, 18_000_000, 13_000_000, true),

        // Higher resolution is exposed only when the phone validates it.
        Target(VideoCodec.HEVC, 2560, 1440, 60, 60_000_000, 45_000_000, false),
        Target(VideoCodec.AVC, 2560, 1440, 60, 80_000_000, 60_000_000, false),
        Target(VideoCodec.HEVC, 2560, 1440, 30, 38_000_000, 28_000_000, false),
        Target(VideoCodec.AVC, 2560, 1440, 30, 52_000_000, 38_000_000, false),
    )

    fun chooseStartupProfile(supported: List<SupportedProfile>): RecordingProfile? {
        val hardware = supported.filter { it.hardwareAccelerated }
        if (hardware.isEmpty()) return null

        val automaticTargets = qualityTargets.filter { it.automatic }

        for (target in automaticTargets) {
            val match = hardware.firstOrNull {
                it.codec == target.codec &&
                    it.width == target.width &&
                    it.height == target.height &&
                    it.fps == target.fps &&
                    it.bitrate >= target.minimumBitrate
            }
            if (match != null) {
                return match.toRecordingProfile()
            }
        }

        return hardware
            .sortedWith(
                compareByDescending<SupportedProfile> { it.width.toLong() * it.height * it.fps }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()
            ?.toRecordingProfile()
    }
}
