package com.medplay.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.medplay.app.recording.AppLanguagePreference
import com.medplay.app.recording.ClipEditProjectStore
import com.medplay.app.recording.ClipLibrary

class ClipEditorActivity : Activity() {

    private lateinit var sourceUri: Uri
    private var sourceDurationUs: Long = 0L
    private var trimStartUs: Long = 0L
    private var trimEndUs: Long = 0L
    private var gameGain = 100
    private var micGain = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = intent.data
        if (source == null) {
            finish()
            return
        }
        sourceUri = source

        val clip = ClipLibrary.find(this, source)
        if (clip == null) {
            finish()
            return
        }
        sourceDurationUs = clip.durationUs.coerceAtLeast(0L)

        val project = ClipEditProjectStore.load(
            context = this,
            sourceUri = source,
            sourceDurationUs = sourceDurationUs,
        )
        trimStartUs = project.trimStartUs
        trimEndUs = project.trimEndUs
        gameGain = project.gameAudioGainPercent
        micGain = project.microphoneGainPercent

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        val videoView = VideoView(this)
        val controller = MediaController(this).apply {
            setAnchorView(videoView)
        }
        videoView.setMediaController(controller)
        videoView.setVideoURI(source)

        val trimStatus = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun refreshTrimStatus() {
            trimStatus.text = tr(
                "Recorte: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
                "Trim: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
            )
        }

        val setStartButton = Button(this).apply {
            text = tr("Marcar inicio aquí", "Set start here")
            setOnClickListener {
                val currentUs = videoView.currentPosition.toLong() * 1_000L
                trimStartUs = currentUs.coerceIn(0L, trimEndUs)
                refreshTrimStatus()
            }
        }

        val setEndButton = Button(this).apply {
            text = tr("Marcar final aquí", "Set end here")
            setOnClickListener {
                val currentUs = videoView.currentPosition.toLong() * 1_000L
                trimEndUs = currentUs.coerceIn(trimStartUs, sourceDurationUs)
                refreshTrimStatus()
            }
        }

        val resetTrimButton = Button(this).apply {
            text = tr("Restablecer recorte", "Reset trim")
            setOnClickListener {
                trimStartUs = 0L
                trimEndUs = sourceDurationUs
                refreshTrimStatus()
            }
        }

        val gameGainLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val gameGainSeek = SeekBar(this).apply {
            max = 200
            progress = gameGain
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        gameGain = progress
                        gameGainLabel.text = tr(
                            "Audio del juego: $progress%",
                            "Game audio: $progress%",
                        )
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                }
            )
        }

        val micGainLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = if (clip.hasMicrophone) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
        val micGainSeek = SeekBar(this).apply {
            max = 200
            progress = micGain
            visibility = if (clip.hasMicrophone) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        micGain = progress
                        micGainLabel.text = tr(
                            "Mi voz: $progress%",
                            "My voice: $progress%",
                        )
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                }
            )
        }

        val saveButton = Button(this).apply {
            text = tr("Guardar edición", "Save edit")
            setOnClickListener {
                ClipEditProjectStore.save(
                    context = this@ClipEditorActivity,
                    project = ClipEditProjectStore.Project(
                        sourceUri = sourceUri,
                        trimStartUs = trimStartUs,
                        trimEndUs = trimEndUs,
                        gameAudioGainPercent = gameGain,
                        microphoneGainPercent = micGain,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                    sourceDurationUs = sourceDurationUs,
                )
                text = tr("Edición guardada ✓", "Edit saved ✓")
            }
        }

        root.addView(
            videoView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        root.addView(trimStatus)
        root.addView(setStartButton)
        root.addView(setEndButton)
        root.addView(resetTrimButton)
        root.addView(gameGainLabel)
        root.addView(gameGainSeek)
        root.addView(micGainLabel)
        root.addView(micGainSeek)
        root.addView(saveButton)

        setContentView(root)
        refreshTrimStatus()
        gameGainLabel.text = tr(
            "Audio del juego: $gameGain%",
            "Game audio: $gameGain%",
        )
        micGainLabel.text = tr(
            "Mi voz: $micGain%",
            "My voice: $micGain%",
        )

        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            val startMs = (trimStartUs / 1_000L).toInt()
            videoView.seekTo(startMs)
            videoView.start()
        }
    }

    private fun formatTime(us: Long): String {
        val totalSeconds = (us / 1_000_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(this, spanish, english)
    }
}
