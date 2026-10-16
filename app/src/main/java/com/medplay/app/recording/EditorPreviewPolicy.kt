package com.medplay.app.recording

object EditorPreviewPolicy {

    /**
     * While playback is requested, Media3 applies updated video effects to
     * subsequent frames. Seeking on every slider tick stalls live preview.
     *
     * When playback is intentionally paused, a same-position seek is allowed
     * as a one-frame redraw request for the newly selected effect values.
     */
    fun shouldForcePausedRedraw(playWhenReady: Boolean): Boolean {
        return !playWhenReady
    }
}
