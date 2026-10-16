package com.medplay.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import com.medplay.app.recording.AppLanguagePreference

class ClipPlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = intent.data
        if (source == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val videoView = VideoView(this)
        val controller = MediaController(this).apply {
            setAnchorView(videoView)
        }
        videoView.setMediaController(controller)
        videoView.setVideoURI(source)
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            showPlaybackError(root, source)
            true
        }

        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        )

        setContentView(root)
    }

    private fun showPlaybackError(root: FrameLayout, source: Uri) {
        root.removeAllViews()
        root.addView(
            TextView(this).apply {
                text = AppLanguagePreference.text(
                    this@ClipPlayerActivity,
                    "No se pudo reproducir este clip dentro de MedPlay.",
                    "This clip could not be played inside MedPlay.",
                )
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                contentDescription = source.toString()
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
    }
}
