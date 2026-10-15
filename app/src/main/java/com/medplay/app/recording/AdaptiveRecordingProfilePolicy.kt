package com.medplay.app.recording

/**
 * Chooses a lower validated hardware profile when Android reports sustained
 * severe thermal pressure.
 *
 * The order intentionally reduces frame rate before resolution where possible
 * so MedPlay sheds encoder/display load while preserving image detail.
 */
object AdaptiveRecordingProfilePolicy {

    private data class Mode(
        val width: Int,
        val height: Int,
        val fps: Int,
        val rank: Int,
    )

    private val modes = listOf(
        Mode(2560, 1440, 60, 6),
        Mode(2560, 1440, 30, 5),
        Mode(1920, 1080, 60, 4),
        Mode(1920, 1080, 30, 3),
        Mode(1280, 720, 60, 2),
        Mode(1280, 720, 30, 1),
    )

    fun nextLowerProfile(
        current: RecordingProfile,
        supported: List<SustainableProfilePolicy.SupportedProfile>,
    ): RecordingProfile? {
        val currentRank = rankOf(current.width, current.height, current.fps)
            ?: return null

        val lower = supported
            .asSequence()
            .filter { it.hardwareAccelerated }
            .mapNotNull { candidate ->
                val rank = rankOf(candidate.width, candidate.height, candidate.fps)
                    ?: return@mapNotNull null
                if (rank >= currentRank) return@mapNotNull null
                Ranked(candidate, rank)
            }
            .toList()

        if (lower.isEmpty()) return null
        val nextRank = lower.maxOf { it.rank }
        val sameStep = lower.filter { it.rank == nextRank }

        return sameStep
            .sortedWith(
                compareByDescending<Ranked> {
                    it.profile.codec == current.codec
                }.thenByDescending {
                    it.profile.encoderName == current.encoderName
                }.thenByDescending {
                    it.profile.bitrate
                }
            )
            .first()
            .profile
            .toRecordingProfile()
    }

    private data class Ranked(
        val profile: SustainableProfilePolicy.SupportedProfile,
        val rank: Int,
    )

    private fun rankOf(width: Int, height: Int, fps: Int): Int? {
        return modes.firstOrNull {
            it.width == width && it.height == height && it.fps == fps
        }?.rank
    }
}
