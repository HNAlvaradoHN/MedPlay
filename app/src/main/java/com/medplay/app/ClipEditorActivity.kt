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
import com.medplay.app.recording.ClipTrimExporter
import kotlin.concurrent.thread

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
        val trimTimeline = ClipTrimTimelineView(this).apply {
            setRange(
                startUs = trimStartUs,
                endUs = trimEndUs,
                durationUs = sourceDurationUs,
            )
        }

        fun refreshTrimStatus() {
            trimStatus.text = tr(
                "Recorte: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
                "Trim: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
            )
            trimTimeline.setRange(
                startUs = trimStartUs,
                endUs = trimEndUs,
                durationUs = sourceDurationUs,
            )
        }

        trimTimeline.setListener(
            ClipTrimTimelineView.Listener { startUs, endUs, fromUser ->
                val previousStart = trimStartUs
                val previousEnd = trimEndUs
                trimStartUs = startUs
                trimEndUs = endUs
                trimStatus.text = tr(
                    "Recorte: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
                    "Trim: ${formatTime(trimStartUs)} → ${formatTime(trimEndUs)}",
                )
                if (fromUser) {
                    val targetUs = if (
                        kotlin.math.abs(trimStartUs - previousStart) >=
                        kotlin.math.abs(trimEndUs - previousEnd)
                    ) {
                        trimStartUs
                    } else {
                        trimEndUs
                    }
                    videoView.seekTo((targetUs / 1_000L).toInt())
                }
            }
        )

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

        fun persistProject() {
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
        }

        val saveButton = Button(this).apply {
            text = tr("Guardar edición", "Save edit")
            setOnClickListener {
                persistProject()
                text = tr("Edición guardada ✓", "Edit saved ✓")
            }
        }

        val exportStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            text = tr(
                "El recorte se exporta como un video nuevo. El original no se modifica.",
                "Trim exports as a new video. The original is not modified.",
            )
        }

        val exportButton = Button(this).apply {
            text = tr("Exportar recorte", "Export trim")
            setOnClickListener {
                if (trimEndUs <= trimStartUs) {
                    exportStatus.text = tr(
                        "Selecciona un tramo válido para exportar.",
                        "Select a valid range to export.",
                    )
                    return@setOnClickListener
                }

                persistProject()
                isEnabled = false
                exportStatus.text = tr(
                    "Exportando sin recodificar el video...",
                    "Exporting without re-encoding video...",
                )

                thread(name = "medplay-editor-trim-export") {
                    val result = runCatching {
                        ClipTrimExporter(this@ClipEditorActivity).export(
                            sourceUri = sourceUri,
                            requestedStartUs = trimStartUs,
                            requestedEndUs = trimEndUs,
                        )
                    }

                    runOnUiThread {
                        isEnabled = true
                        result.onSuccess { exported ->
                            ClipLibrary.recordDerivedExport(
                                context = this@ClipEditorActivity,
                                uri = exported.uri,
                                displayName = exported.displayName,
                                durationUs = exported.durationUs,
                                hasGameAudio = clip.hasGameAudio,
                                hasMicrophone = clip.hasMicrophone,
                            )
                            val adjustmentUs =
                                trimStartUs - exported.actualStartUs
                            exportStatus.text = if (adjustmentUs > 100_000L) {
                                tr(
                                    "Exportado ✓ • inicio ajustado " +
                                        "${formatPreciseTime(exported.actualStartUs)} " +
                                        "para conservar calidad sin recodificar.",
                                    "Exported ✓ • start adjusted to " +
                                        "${formatPreciseTime(exported.actualStartUs)} " +
                                        "to preserve quality without re-encoding.",
                                )
                            } else {
                                tr(
                                    "Exportado ✓ • disponible en Mis clips y Galería.",
                                    "Exported ✓ • available in My clips and Gallery.",
                                )
                            }
                        }.onFailure {
                            exportStatus.text = tr(
                                "No se pudo exportar el recorte.",
                                "The trim could not be exported.",
                            )
                        }
                    }
                }
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
        root.addView(trimTimeline)
        root.addView(setStartButton)
        root.addView(setEndButton)
        root.addView(resetTrimButton)
        root.addView(gameGainLabel)
        root.addView(gameGainSeek)
        root.addView(micGainLabel)
        root.addView(micGainSeek)
        root.addView(saveButton)
        root.addView(exportStatus)
        root.addView(exportButton)

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

    private fun formatPreciseTime(us: Long): String {
        val totalTenths = (us / 100_000L).coerceAtLeast(0L)
        val minutes = totalTenths / 600L
        val seconds = (totalTenths % 600L) / 10L
        val tenths = totalTenths % 10L
        return "%02d:%02d.%d".format(minutes, seconds, tenths)
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(this, spanish, english)
    }
}
