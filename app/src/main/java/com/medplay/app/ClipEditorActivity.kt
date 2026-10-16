package com.medplay.app

import android.app.Activity
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
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
import com.medplay.app.recording.EditorAudioPreviewController
import com.medplay.app.recording.ClipLibrary
import com.medplay.app.recording.ClipFinalExporter
import com.medplay.app.recording.ClipTrimExporter
import com.medplay.app.recording.EditorVisualSettings
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class ClipEditorActivity : Activity() {

    private lateinit var sourceUri: Uri
    private lateinit var player: ExoPlayer
    private var audioPreviewController: EditorAudioPreviewController? = null
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
        val playerView = layoutInflater.inflate(
            R.layout.editor_player_view,
            null,
        ) as PlayerView
        playerView.player = player
        val previewSurface = playerView.videoSurfaceView ?: playerView

        player.setMediaItem(MediaItem.fromUri(source))
        player.prepare()
        player.seekTo((trimStartUs / 1_000L).coerceAtLeast(0L))

        // Hold playback until the independent audio preview tracks are ready.
        // Starting the video first and repeatedly catching audio up with seeks
        // is what made the preview sound chopped.
        player.playWhenReady = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.56f,
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
                0.44f,
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
            val matrix = buildPreviewColorMatrix(currentVisualSettings())
            val colorFilter = ColorMatrixColorFilter(matrix)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewSurface.setRenderEffect(
                    RenderEffect.createColorFilterEffect(colorFilter)
                )
            } else {
                previewSurface.setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply { this.colorFilter = colorFilter },
                )
            }

            previewSurface.rotation = rotationDegrees.toFloat()
            previewSurface.invalidate()
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
        val audioPreviewStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            text = tr(
                "Preparando vista previa de audio...",
                "Preparing audio preview...",
            )
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
                    audioPreviewController?.setGains(
                        gameGainPercent = gameGain,
                        microphoneGainPercent = micGain,
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
                    audioPreviewController?.setGains(
                        gameGainPercent = gameGain,
                        microphoneGainPercent = micGain,
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

        val finalExportButton = Button(this).apply {
            text = tr(
                "Exportar video final",
                "Export final video",
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
                quickExportButton.isEnabled = false
                exportStatus.text = tr(
                    "Procesando video y mezclando audio...",
                    "Processing video and mixing audio...",
                )

                ClipFinalExporter(this@ClipEditorActivity).start(
                    sourceUri = sourceUri,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    hasGameAudio = clip.hasGameAudio,
                    hasMicrophone = clip.hasMicrophone,
                    gameGainPercent = gameGain,
                    microphoneGainPercent = micGain,
                    visualSettings = currentVisualSettings(),
                    onSuccess = { exported ->
                        ClipLibrary.recordDerivedExport(
                            context = this@ClipEditorActivity,
                            uri = exported.uri,
                            displayName = exported.displayName,
                            durationUs = exported.durationUs,
                            hasGameAudio =
                                clip.hasGameAudio || clip.hasMicrophone,
                            hasMicrophone = false,
                        )
                        isEnabled = true
                        quickExportButton.isEnabled = true
                        exportStatus.text = tr(
                            "Video final exportado ✓ • audio mezclado en una pista compatible.",
                            "Final video exported ✓ • audio mixed into one compatible track.",
                        )
                    },
                    onError = {
                        isEnabled = true
                        quickExportButton.isEnabled = true
                        exportStatus.text = tr(
                            "No se pudo exportar el video final.",
                            "Final video export failed.",
                        )
                    },
                )
            }
        }

        val finalExportNote = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            text = tr(
                "Exportación final: aplica recorte, filtros, rotación y volúmenes. Si hay juego + voz, los mezcla en una sola pista para que se escuchen juntos en reproductores normales.",
                "Final export applies trim, filters, rotation and volumes. If game + voice are present, they are mixed into one track for normal players.",
            )
        }

        fun makeSection(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    0,
                    MediaUiStyle.dp(this@ClipEditorActivity, 12),
                    0,
                    MediaUiStyle.dp(this@ClipEditorActivity, 20),
                )
            }
        }

        val trimSection = makeSection().apply {
            addView(trimStatus)
            addView(trimTimeline)
            addView(setStartButton)
            addView(setEndButton)
            addView(resetTrimButton)
        }

        val visualSection = makeSection().apply {
            addView(visualTitle)
            addView(filterSpinner)
            addView(saturationLabel)
            addView(saturationSeek)
            addView(brightnessLabel)
            addView(brightnessSeek)
            addView(contrastLabel)
            addView(contrastSeek)
            addView(rotationSpinner)
        }

        val audioSection = makeSection().apply {
            addView(audioTitle)
            addView(audioPreviewStatus)
            addView(gameGainLabel)
            addView(gameGainSeek)
            addView(micGainLabel)
            addView(micGainSeek)
        }

        val exportSection = makeSection().apply {
            addView(saveButton)
            addView(exportStatus)
            addView(quickExportButton)
            addView(finalExportButton)
            addView(finalExportNote)
        }

        val sections = listOf(
            trimSection,
            visualSection,
            audioSection,
            exportSection,
        )

        val toolButtons = mutableListOf<TextView>()
        fun toolButton(
            label: String,
            sectionIndex: Int,
        ): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(MediaUiStyle.SECONDARY_TEXT)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(
                    MediaUiStyle.dp(this@ClipEditorActivity, 18),
                    MediaUiStyle.dp(this@ClipEditorActivity, 10),
                    MediaUiStyle.dp(this@ClipEditorActivity, 18),
                    MediaUiStyle.dp(this@ClipEditorActivity, 10),
                )
                background = MediaUiStyle.rounded(
                    MediaUiStyle.SURFACE,
                    14,
                    this@ClipEditorActivity,
                )
                setOnClickListener {
                    sections.forEachIndexed { index, section ->
                        section.visibility =
                            if (index == sectionIndex) View.VISIBLE else View.GONE
                    }
                    toolButtons.forEachIndexed { index, button ->
                        button.setTextColor(
                            if (index == sectionIndex) {
                                Color.WHITE
                            } else {
                                MediaUiStyle.SECONDARY_TEXT
                            }
                        )
                        button.background = MediaUiStyle.rounded(
                            if (index == sectionIndex) {
                                MediaUiStyle.ACCENT
                            } else {
                                MediaUiStyle.SURFACE
                            },
                            14,
                            this@ClipEditorActivity,
                        )
                    }
                }
            }
        }

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            tr("Recorte", "Trim"),
            tr("Imagen", "Visual"),
            tr("Audio", "Audio"),
            tr("Exportar", "Export"),
        ).forEachIndexed { index, label ->
            val button = toolButton(label, index)
            toolButtons += button
            toolRow.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = MediaUiStyle.dp(
                        this@ClipEditorActivity,
                        8,
                    )
                }
            )
        }

        val toolRail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolRow)
        }

        controls.addView(toolRail)
        sections.forEach { section ->
            controls.addView(section)
        }

        sections.forEachIndexed { index, section ->
            section.visibility =
                if (index == 0) View.VISIBLE else View.GONE
        }
        toolButtons.firstOrNull()?.let { first ->
            first.setTextColor(Color.WHITE)
            first.background = MediaUiStyle.rounded(
                MediaUiStyle.ACCENT,
                14,
                this@ClipEditorActivity,
            )
        }

        setContentView(root)
        applyPreviewEffects()
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

        val expectedAudioTracks =
            (if (clip.hasGameAudio) 1 else 0) +
                (if (clip.hasMicrophone) 1 else 0)

        audioPreviewController = EditorAudioPreviewController(
            context = this,
            sourceUri = sourceUri,
            videoPlayer = player,
            expectedTrackCount = expectedAudioTracks,
            hasGameAudio = clip.hasGameAudio,
            hasMicrophone = clip.hasMicrophone,
            onReady = {
                audioPreviewStatus.text = if (clip.hasMicrophone) {
                    tr(
                        "Vista previa activa: juego y voz se escuchan juntos.",
                        "Preview active: game and voice are mixed for listening.",
                    )
                } else {
                    tr(
                        "Vista previa de volumen activa.",
                        "Volume preview active.",
                    )
                }
                player.playWhenReady = true
            },
            onUnavailable = {
                audioPreviewStatus.text = tr(
                    "No se pudo preparar la vista previa independiente de audio; el video seguirá usando su audio normal.",
                    "Independent audio preview is unavailable; normal video audio will continue.",
                )
                player.playWhenReady = true
            },
        ).also {
            it.setGains(
                gameGainPercent = gameGain,
                microphoneGainPercent = micGain,
            )
        }
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

    private fun buildPreviewColorMatrix(
        values: EditorVisualSettings.Values,
    ): ColorMatrix {
        val safe = EditorVisualSettings.sanitize(values)
        val matrix = ColorMatrix()

        fun concatSaturation(scale: Float) {
            matrix.postConcat(
                ColorMatrix().apply { setSaturation(scale.coerceAtLeast(0f)) }
            )
        }

        fun concatScale(red: Float, green: Float, blue: Float) {
            matrix.postConcat(
                ColorMatrix().apply { setScale(red, green, blue, 1f) }
            )
        }

        fun concatBrightness(percent: Int) {
            if (percent == 0) return
            val offset = 255f * (percent / 100f)
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, offset,
                        0f, 1f, 0f, 0f, offset,
                        0f, 0f, 1f, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }

        fun concatContrast(amount: Float) {
            if (amount == 0f) return
            val scale = (1f + amount).coerceAtLeast(0f)
            val offset = 128f * (1f - scale)
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, offset,
                        0f, scale, 0f, 0f, offset,
                        0f, 0f, scale, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }

        when (safe.filterPreset) {
            EditorVisualSettings.FilterPreset.NATURAL -> Unit
            EditorVisualSettings.FilterPreset.VIVID -> {
                concatSaturation(1.20f)
                concatContrast(0.12f)
            }
            EditorVisualSettings.FilterPreset.WARM ->
                concatScale(1.08f, 1.01f, 0.92f)
            EditorVisualSettings.FilterPreset.COOL ->
                concatScale(0.94f, 1.00f, 1.08f)
            EditorVisualSettings.FilterPreset.CINEMATIC -> {
                concatSaturation(0.92f)
                concatBrightness(-3)
                concatContrast(0.18f)
            }
            EditorVisualSettings.FilterPreset.MONO ->
                concatSaturation(0f)
        }

        concatSaturation(safe.saturationPercent / 100f)
        concatBrightness(safe.brightnessPercent)
        concatContrast(safe.contrastPercent / 100f)

        return matrix
    }

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
        audioPreviewController?.release()
        audioPreviewController = null
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(this, spanish, english)
    }
}
