package com.medplay.app.recording

/**
 * Conservative startup policy.
 *
 * This does not try to prove long-term thermal sustainability from codec
 * capability metadata alone. It chooses a hardware-backed profile that is
 * known to be supported, then runtime health monitoring can raise/lower load.
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
        val bitrate: Int,
    )

    /**
     * Startup targets intentionally stop below 1080p60.
     * 60 FPS and higher-resolution modes should be promoted only after
     * sustained runtime health checks show headroom on the device.
     */
    val startupTargets: List<Target> = listOf(
        Target(VideoCodec.HEVC, 1920, 1080, 30, 12_000_000),
        Target(VideoCodec.AVC, 1920, 1080, 30, 16_000_000),
        Target(VideoCodec.HEVC, 1280, 720, 60, 10_000_000),
        Target(VideoCodec.AVC, 1280, 720, 60, 14_000_000),
        Target(VideoCodec.HEVC, 1280, 720, 30, 7_000_000),
        Target(VideoCodec.AVC, 1280, 720, 30, 10_000_000),
    )

    fun chooseStartupProfile(supported: List<SupportedProfile>): RecordingProfile? {
        val hardware = supported.filter { it.hardwareAccelerated }
        if (hardware.isEmpty()) return null

        for (target in startupTargets) {
            val match = hardware.firstOrNull {
                it.codec == target.codec &&
                    it.width == target.width &&
                    it.height == target.height &&
                    it.fps == target.fps &&
                    it.bitrate >= target.bitrate
            }
            if (match != null) {
                return match.copy(bitrate = target.bitrate).toRecordingProfile()
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
