package com.medplay.app.recording

object FinalExportAudioPolicy {

    fun gainsForTracks(
        audioTrackCount: Int,
        hasGameAudio: Boolean,
        hasMicrophone: Boolean,
        gameGainPercent: Int,
        microphoneGainPercent: Int,
    ): List<Int> {
        val expectedTrackCount =
            (if (hasGameAudio) 1 else 0) +
                (if (hasMicrophone) 1 else 0)

        require(audioTrackCount == expectedTrackCount) {
            "Audio track mismatch: expected $expectedTrackCount, found $audioTrackCount"
        }

        return buildList {
            if (hasGameAudio) {
                add(gameGainPercent.coerceIn(0, 200))
            }
            if (hasMicrophone) {
                add(microphoneGainPercent.coerceIn(0, 200))
            }
        }
    }
}
