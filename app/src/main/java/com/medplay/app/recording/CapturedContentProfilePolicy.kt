package com.medplay.app.recording

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preserves the aspect ratio of the content Android is actually capturing while
 * keeping the selected quality class bounded by its original long side.
 *
 * Example: a 1080p profile (1920 long side) on a 1080x2400 phone becomes
 * approximately 864x1920 instead of forcing the screen into 1080x1920.
 */
object CapturedContentProfilePolicy {

    fun fit(
        profile: RecordingProfile,
        capturedWidth: Int,
        capturedHeight: Int,
        alignment: Int = 2,
    ): RecordingProfile {
        require(capturedWidth > 0)
        require(capturedHeight > 0)
        require(alignment > 0)

        val targetLongSide = max(profile.width, profile.height)
        val capturedLongSide = max(capturedWidth, capturedHeight)
        val capturedShortSide = min(capturedWidth, capturedHeight)

        val scaledShortSide =
            targetLongSide.toDouble() * capturedShortSide / capturedLongSide
        val alignedShortSide = (
            scaledShortSide / alignment.toDouble()
            ).roundToInt()
            .coerceAtLeast(1) * alignment

        return if (capturedHeight > capturedWidth) {
            profile.copy(
                width = alignedShortSide,
                height = targetLongSide,
            )
        } else {
            profile.copy(
                width = targetLongSide,
                height = alignedShortSide,
            )
        }
    }
}
