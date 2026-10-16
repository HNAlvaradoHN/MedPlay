package com.medplay.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.medplay.app.recording.AppLanguagePreference
import com.medplay.app.recording.ClipEditProjectStore
import com.medplay.app.recording.ClipLibrary
import com.medplay.app.recording.ClipTrimExporter
import com.medplay.app.recording.ClipVisualExporter
import com.medplay.app.recording.EditorVisualSettings
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class ClipEditorActivity : Activity() {

    private lateinit var sourceUri: Uri
    private lateinit var player: ExoPlayer
    private var sourceDurationUs: Long = 0L
    private var trimStartUs: Long = 0L
    private var trimEndUs: Long = 0L
    private var gameGain = 100
    private var micGain = 100
    private var filterPreset = EditorVisualSettings.FilterPreset.NATURAL
    private var saturationPercent = 100
    private var brightnessPercent = 0
    private var contrastPercent = 0
    private var rotationDegrees = 0

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
        filterPreset = project.filterPreset
        saturationPercent = project.saturationPercent
        brightnessPercent = project.brightnessPercent
        contrastPercent = project.contrastPercent
        rotationDegrees = project.rotationDegrees

        player = ExoPlayer.Builder(this).build()
        val playerView = PlayerView(this).apply {
            player = this@ClipEditorActivity.player
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        player.setMediaItem(MediaItem.fromUri(source))
        player.setVideoEffects(currentVideoEffects())
        player.prepare()
        player.seekTo((trimStartUs / 1_000L).coerceAtLeast(0L))
        player.playWhenReady = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.48f,
            )
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 32)
        }
        val scroll = ScrollView(this).apply {
            addView(
                controls,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.52f,
            )
        )

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
                    player.seekTo((targetUs / 1_000L).coerceAtLeast(0L))
                }
            }
        )

        val setStartButton = Button(this).apply {
            text = tr("Marcar inicio aquí", "Set start here")
            setOnClickListener {
                val currentUs = player.currentPosition * 1_000L
                trimStartUs = currentUs.coerceIn(0L, trimEndUs)
                refreshTrimStatus()
            }
        }
        val setEndButton = Button(this).apply {
            text = tr("Marcar final aquí", "Set end here")
            setOnClickListener {
                val currentUs = player.currentPosition * 1_000L
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

        val visualTitle = TextView(this).apply {
            text = tr("Color y filtros", "Color & filters")
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 20, 0, 8)
        }

        val filterOptions = EditorVisualSettings.FilterPreset.entries
        val filterSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ClipEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                filterOptions.map(::filterLabel),
            )
            setSelection(filterOptions.indexOf(filterPreset).coerceAtLeast(0))
        }

        val saturationLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val saturationSeek = SeekBar(this).apply {
            max = 200
            progress = saturationPercent
        }

        val brightnessLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val brightnessSeek = SeekBar(this).apply {
            max = 100
            progress = brightnessPercent + 50
        }

        val contrastLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val contrastSeek = SeekBar(this).apply {
            max = 100
            progress = contrastPercent + 50
        }

        val rotationOptions = listOf(0, 90, 180, 270)
        val rotationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ClipEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                rotationOptions.map { degrees ->
                    tr("Rotación $degrees°", "Rotation $degrees°")
                },
            )
            setSelection(
                rotationOptions.indexOf(rotationDegrees).coerceAtLeast(0)
            )
        }

        fun refreshVisualLabels() {
            saturationLabel.text = tr(
                "Intensidad de color: $saturationPercent%",
                "Color intensity: $saturationPercent%",
            )
            brightnessLabel.text = tr(
                "Brillo: ${signed(brightnessPercent)}",
                "Brightness: ${signed(brightnessPercent)}",
            )
            contrastLabel.text = tr(
                "Contraste: ${signed(contrastPercent)}",
                "Contrast: ${signed(contrastPercent)}",
            )
        }

        fun applyPreviewEffects() {
            player.setVideoEffects(currentVideoEffects())
            val position = player.currentPosition
            player.seekTo(position)
        }

        filterSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = filterOptions[position]
                    if (selected != filterPreset) {
                        filterPreset = selected
                        applyPreviewEffects()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        saturationSeek.setOnSeekBarChangeListener(
            simpleSeekListener { value, fromUser ->
                saturationPercent = value
                refreshVisualLabels()
                if (fromUser) applyPreviewEffects()
            }
        )
        brightnessSeek.setOnSeekBarChangeListener(
            simpleSeekListener { value, fromUser ->
                brightnessPercent = value - 50
                refreshVisualLabels()
                if (fromUser) applyPreviewEffects()
            }
        )
        contrastSeek.setOnSeekBarChangeListener(
            simpleSeekListener { value, fromUser ->
                contrastPercent = value - 50
                refreshVisualLabels()
                if (fromUser) applyPreviewEffects()
            }
        )

        rotationSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = rotationOptions[position]
                    if (selected != rotationDegrees) {
                        rotationDegrees = selected
                        applyPreviewEffects()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        val audioTitle = TextView(this).apply {
            text = tr("Audio", "Audio")
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 20, 0, 8)
        }
        val gameGainLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val gameGainSeek = SeekBar(this).apply {
            max = 200
            progress = gameGain
            setOnSeekBarChangeListener(
                simpleSeekListener { value, _ ->
                    gameGain = value
                    gameGainLabel.text = tr(
                        "Audio del juego: $value%",
                        "Game audio: $value%",
                    )
                }
            )
        }

        val micGainLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = if (clip.hasMicrophone) View.VISIBLE else View.GONE
        }
        val micGainSeek = SeekBar(this).apply {
            max = 200
            progress = micGain
            visibility = if (clip.hasMicrophone) View.VISIBLE else View.GONE
            setOnSeekBarChangeListener(
                simpleSeekListener { value, _ ->
                    micGain = value
                    micGainLabel.text = tr(
                        "Mi voz: $value%",
                        "My voice: $value%",
                    )
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
                    filterPreset = filterPreset,
                    saturationPercent = saturationPercent,
                    brightnessPercent = brightnessPercent,
                    contrastPercent = contrastPercent,
                    rotationDegrees = rotationDegrees,
                    updatedAtMs = System.currentTimeMillis(),
                ),
                sourceDurationUs = sourceDurationUs,
            )
        }

        val saveButton = Button(this).apply {
            text = tr("Guardar proyecto", "Save project")
            setOnClickListener {
                persistProject()
                text = tr("Proyecto guardado ✓", "Project saved ✓")
            }
        }

        val exportStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val quickExportButton = Button(this).apply {
            text = tr(
                "Exportar rápido (sin filtros)",
                "Fast export (no filters)",
            )
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
                            exportStatus.text = tr(
                                "Exportado ✓ • Mis clips y Galería.",
                                "Exported ✓ • My clips and Gallery.",
                            )
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

        val visualExportButton = Button(this).apply {
            text = tr(
                "Exportar con color y filtros",
                "Export with color & filters",
            )
            isEnabled = !clip.hasMicrophone
            setOnClickListener {
                if (trimEndUs <= trimStartUs) {
                    exportStatus.text = tr(
                        "Selecciona un tramo válido para exportar.",
                        "Select a valid range to export.",
                    )
                    return@setOnClickListener
                }
                if (!EditorVisualSettings.hasVisualChanges(currentVisualSettings())) {
                    exportStatus.text = tr(
                        "No hay cambios visuales. Usa Exportar rápido.",
                        "No visual changes. Use Fast export.",
                    )
                    return@setOnClickListener
                }

                persistProject()
                isEnabled = false
                quickExportButton.isEnabled = false
                exportStatus.text = tr(
                    "Procesando color con aceleración del dispositivo...",
                    "Processing color with device acceleration...",
                )

                ClipVisualExporter(this@ClipEditorActivity).start(
                    sourceUri = sourceUri,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    visualSettings = currentVisualSettings(),
                    onSuccess = { exported ->
                        ClipLibrary.recordDerivedExport(
                            context = this@ClipEditorActivity,
                            uri = exported.uri,
                            displayName = exported.displayName,
                            durationUs = exported.durationUs,
                            hasGameAudio = clip.hasGameAudio,
                            hasMicrophone = false,
                        )
                        isEnabled = true
                        quickExportButton.isEnabled = true
                        exportStatus.text = tr(
                            "Exportado con filtros ✓ • Mis clips y Galería.",
                            "Filtered export complete ✓ • My clips and Gallery.",
                        )
                    },
                    onError = {
                        isEnabled = true
                        quickExportButton.isEnabled = true
                        exportStatus.text = tr(
                            "No se pudo exportar con filtros.",
                            "Filtered export failed.",
                        )
                    },
                )
            }
        }

        val microphoneExportNote = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            visibility = if (clip.hasMicrophone) View.VISIBLE else View.GONE
            text = tr(
                "Este clip tiene voz separada. La exportación con filtros se habilitará al completar la mezcla de audio para no perder esa pista.",
                "This clip has a separate voice track. Filtered export will be enabled after audio mixing is completed so that track is not lost.",
            )
        }

        controls.addView(trimStatus)
        controls.addView(trimTimeline)
        controls.addView(setStartButton)
        controls.addView(setEndButton)
        controls.addView(resetTrimButton)
        controls.addView(visualTitle)
        controls.addView(filterSpinner)
        controls.addView(saturationLabel)
        controls.addView(saturationSeek)
        controls.addView(brightnessLabel)
        controls.addView(brightnessSeek)
        controls.addView(contrastLabel)
        controls.addView(contrastSeek)
        controls.addView(rotationSpinner)
        controls.addView(audioTitle)
        controls.addView(gameGainLabel)
        controls.addView(gameGainSeek)
        controls.addView(micGainLabel)
        controls.addView(micGainSeek)
        controls.addView(saveButton)
        controls.addView(exportStatus)
        controls.addView(quickExportButton)
        controls.addView(visualExportButton)
        controls.addView(microphoneExportNote)

        setContentView(root)
        refreshTrimStatus()
        refreshVisualLabels()
        gameGainLabel.text = tr(
            "Audio del juego: $gameGain%",
            "Game audio: $gameGain%",
        )
        micGainLabel.text = tr(
            "Mi voz: $micGain%",
            "My voice: $micGain%",
        )
        exportStatus.text = tr(
            "El original siempre permanece intacto.",
            "The original always remains intact.",
        )
    }

    private fun currentVisualSettings(): EditorVisualSettings.Values {
        return EditorVisualSettings.Values(
            filterPreset = filterPreset,
            saturationPercent = saturationPercent,
            brightnessPercent = brightnessPercent,
            contrastPercent = contrastPercent,
            rotationDegrees = rotationDegrees,
        )
    }

    private fun currentVideoEffects() =
        EditorVisualSettings.buildVideoEffects(currentVisualSettings())

    private fun simpleSeekListener(
        onChange: (value: Int, fromUser: Boolean) -> Unit,
    ): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                onChange(progress, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun filterLabel(
        preset: EditorVisualSettings.FilterPreset,
    ): String {
        return when (preset) {
            EditorVisualSettings.FilterPreset.NATURAL ->
                tr("Natural", "Natural")
            EditorVisualSettings.FilterPreset.VIVID ->
                tr("Vivo", "Vivid")
            EditorVisualSettings.FilterPreset.WARM ->
                tr("Cálido", "Warm")
            EditorVisualSettings.FilterPreset.COOL ->
                tr("Frío", "Cool")
            EditorVisualSettings.FilterPreset.CINEMATIC ->
                tr("Cinemático", "Cinematic")
            EditorVisualSettings.FilterPreset.MONO ->
                tr("Blanco y negro", "Black & white")
        }
    }

    private fun signed(value: Int): String {
        return if (value > 0) "+$value" else value.toString()
    }

    private fun formatTime(us: Long): String {
        val totalSeconds = (us / 1_000_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(this, spanish, english)
    }
}
