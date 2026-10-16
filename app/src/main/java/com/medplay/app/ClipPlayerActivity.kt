package com.medplay.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val videoContainer = FrameLayout(this).apply {
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
            showPlaybackError(videoContainer, source)
            true
        }

        videoContainer.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        )

        root.addView(
            videoContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        root.addView(
            Button(this).apply {
                text = AppLanguagePreference.text(
                    this@ClipPlayerActivity,
                    "Editar clip",
                    "Edit clip",
                )
                setOnClickListener {
                    startActivity(
                        android.content.Intent(
                            this@ClipPlayerActivity,
                            ClipEditorActivity::class.java,
                        ).apply {
                            data = source
                        }
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
