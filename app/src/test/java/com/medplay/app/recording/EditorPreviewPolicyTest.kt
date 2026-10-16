package com.medplay.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreviewPolicyTest {

    @Test
    fun doesNotSeekWhilePlaybackIsRequested() {
        assertFalse(
            EditorPreviewPolicy.shouldForcePausedRedraw(
                playWhenReady = true,
            )
        )
    }

    @Test
    fun allowsOneFrameRedrawWhenPaused() {
        assertTrue(
            EditorPreviewPolicy.shouldForcePausedRedraw(
                playWhenReady = false,
            )
        )
    }
}
