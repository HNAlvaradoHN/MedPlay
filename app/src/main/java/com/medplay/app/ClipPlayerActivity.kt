package com.medplay.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import java.text.DateFormat
import java.util.Date
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import com.medplay.app.recording.AppLanguagePreference
import com.medplay.app.recording.ClipLibrary

class ClipPlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = intent.data
        if (source == null) {
            finish()
            return
        }
        val clip = ClipLibrary.find(this, source)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MediaUiStyle.BACKGROUND)
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

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                MediaUiStyle.dp(this@ClipPlayerActivity, 14),
                MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                MediaUiStyle.dp(this@ClipPlayerActivity, 18),
            )
            setBackgroundColor(MediaUiStyle.SURFACE)
        }

        details.addView(
            TextView(this).apply {
                text = clip?.createdAtMs?.let {
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                    ).format(Date(it))
                } ?: AppLanguagePreference.text(
                    this@ClipPlayerActivity,
                    "Video de MedPlay",
                    "MedPlay video",
                )
                setTextColor(Color.WHITE)
                textSize = 16f
            }
        )

        clip?.let {
            details.addView(
                TextView(this).apply {
                    val durationSeconds =
                        (it.durationUs / 1_000_000L).coerceAtLeast(0L)
                    val audio = when {
                        it.hasGameAudio && it.hasMicrophone ->
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Juego + voz",
                                "Game + voice",
                            )
                        it.hasGameAudio ->
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Audio del juego",
                                "Game audio",
                            )
                        it.hasMicrophone ->
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Voz",
                                "Voice",
                            )
                        else ->
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Sin audio",
                                "No audio",
                            )
                    }
                    text = "%02d:%02d • %s".format(
                        durationSeconds / 60L,
                        durationSeconds % 60L,
                        audio,
                    )
                    setTextColor(MediaUiStyle.SECONDARY_TEXT)
                    textSize = 12f
                }
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                0,
                MediaUiStyle.dp(this@ClipPlayerActivity, 14),
                0,
                0,
            )
        }

        val editButton = TextView(this).apply {
            text = AppLanguagePreference.text(
                this@ClipPlayerActivity,
                "Editar",
                "Edit",
            )
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(
                MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                MediaUiStyle.dp(this@ClipPlayerActivity, 11),
                MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                MediaUiStyle.dp(this@ClipPlayerActivity, 11),
            )
            background = MediaUiStyle.rounded(
                MediaUiStyle.ACCENT,
                14,
                this@ClipPlayerActivity,
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
        }
        actions.addView(
            editButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        )

        actions.addView(
            TextView(this).apply {
                text = AppLanguagePreference.text(
                    this@ClipPlayerActivity,
                    "Borrar",
                    "Delete",
                )
                setTextColor(MediaUiStyle.DANGER)
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(
                    MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                    MediaUiStyle.dp(this@ClipPlayerActivity, 11),
                    MediaUiStyle.dp(this@ClipPlayerActivity, 18),
                    MediaUiStyle.dp(this@ClipPlayerActivity, 11),
                )
                background = MediaUiStyle.rounded(
                    MediaUiStyle.SURFACE_ELEVATED,
                    14,
                    this@ClipPlayerActivity,
                )
                setOnClickListener {
                    AlertDialog.Builder(this@ClipPlayerActivity)
                        .setTitle(
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "¿Borrar este video?",
                                "Delete this video?",
                            )
                        )
                        .setMessage(
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Se eliminará de MedPlay y de la Galería. Esta acción no se puede deshacer.",
                                "It will be removed from MedPlay and the Gallery. This action cannot be undone.",
                            )
                        )
                        .setNegativeButton(
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Cancelar",
                                "Cancel",
                            ),
                            null,
                        )
                        .setPositiveButton(
                            AppLanguagePreference.text(
                                this@ClipPlayerActivity,
                                "Borrar",
                                "Delete",
                            )
                        ) { _, _ ->
                            videoView.stopPlayback()
                            val deleted = ClipLibrary.delete(
                                this@ClipPlayerActivity,
                                source,
                            )
                            if (deleted) {
                                setResult(RESULT_OK)
                                finish()
                            } else {
                                AlertDialog.Builder(this@ClipPlayerActivity)
                                    .setMessage(
                                        AppLanguagePreference.text(
                                            this@ClipPlayerActivity,
                                            "No se pudo borrar el video.",
                                            "The video could not be deleted.",
                                        )
                                    )
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                        .show()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = MediaUiStyle.dp(
                    this@ClipPlayerActivity,
                    10,
                )
            }
        )

        details.addView(actions)
        root.addView(
            details,
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
