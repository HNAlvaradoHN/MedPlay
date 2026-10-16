package com.medplay.app.recording

/**
 * Keeps the selected quality class while matching the capture orientation.
 */
object RecordingOrientationPolicy {

    fun isPortrait(displayWidth: Int, displayHeight: Int): Boolean {
        return displayHeight > displayWidth
    }

    fun orient(profile: RecordingProfile, portrait: Boolean): RecordingProfile {
        val currentlyPortrait = profile.height > profile.width
        if (currentlyPortrait == portrait) return profile

        return profile.copy(
            width = profile.height,
            height = profile.width,
        )
    }
}
