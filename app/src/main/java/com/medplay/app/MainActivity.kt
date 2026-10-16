package com.medplay.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.medplay.app.recording.AppLanguagePreference
import com.medplay.app.recording.CaptureService
import com.medplay.app.recording.ClipLibrary
import com.medplay.app.recording.ClipLengthPreference
import com.medplay.app.recording.EncoderCapabilityProbe
import com.medplay.app.recording.FloatingControlStylePreference
import com.medplay.app.recording.GlassClipView
import com.medplay.app.recording.InternalAudioPreference
import com.medplay.app.recording.MicrophoneVoicePreference
import com.medplay.app.recording.RecordingModePreference
import com.medplay.app.recording.RecordingQualityPreference
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var clipLibraryContainer: LinearLayout
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingCaptureAfterOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val currentLanguage = AppLanguagePreference.get(this)

        statusView = TextView(this).apply {
            text = tr(
                "Listo para configurar la grabación.",
                "Ready to configure recording.",
            )
            textSize = 16f
        }

        reportView = TextView(this).apply {
            text = tr(
                "Todavía no hay diagnóstico mostrado.",
                "No diagnostics shown yet.",
            )
            textSize = 14f
        }

        val languageLabel = TextView(this).apply {
            text = tr("Idioma", "Language")
            textSize = 14f
        }

        val languages = listOf(
            AppLanguagePreference.Language.SPANISH,
            AppLanguagePreference.Language.ENGLISH,
        )
        val languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Español", "English"),
            )
            setSelection(languages.indexOf(currentLanguage).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = languages[position]
                    if (selected != AppLanguagePreference.get(this@MainActivity)) {
                        AppLanguagePreference.set(this@MainActivity, selected)
                        recreate()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val captureChoiceInfoView = TextView(this).apply {
            text = if (Build.VERSION.SDK_INT >= 34) {
                tr(
                    "Al iniciar, Android te preguntará qué grabar. Elige una app para grabar solo el juego; Pantalla completa graba todo lo que aparece en el teléfono.",
                    "When you start, Android will ask what to capture. Choose one app to record only the game; Full screen records everything shown on the phone.",
                )
            } else {
                tr(
                    "Este Android grabará la pantalla completa.",
                    "This Android version will record the full screen.",
                )
            }
            textSize = 13f
        }

        val recordingModeLabel = TextView(this).apply {
            text = tr("Modo de grabación", "Recording mode")
            textSize = 14f
        }

        val recordingModes = listOf(
            RecordingModePreference.Mode.RETROACTIVE_CLIP,
            RecordingModePreference.Mode.FULL_SESSION,
        )
        val recordingModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    tr("Clip retroactivo", "Retroactive clip"),
                    tr("Grabación completa", "Full-session recording"),
                ),
            )
            setSelection(
                recordingModes.indexOf(
                    RecordingModePreference.get(this@MainActivity)
                ).coerceAtLeast(0)
            )
        }

        val internalAudioToggle = CheckBox(this).apply {
            text = tr("Grabar audio interno", "Capture internal audio")
            isChecked = InternalAudioPreference.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                InternalAudioPreference.setEnabled(this@MainActivity, enabled)
            }
        }

        val microphoneVoiceToggle = CheckBox(this).apply {
            text = tr(
                "Grabar mi voz (pista separada)",
                "Capture my voice (separate track)",
            )
            isChecked = MicrophoneVoicePreference.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                MicrophoneVoicePreference.setEnabled(this@MainActivity, enabled)
            }
        }

        val microphoneVoiceInfo = TextView(this).apply {
            text = tr(
                "Si el juego toma el micrófono para el chat, Android puede pausar solo la pista de tu voz. MedPlay avisará y la reanudará automáticamente cuando vuelva a estar disponible.",
                "If the game takes the microphone for voice chat, Android may pause only your voice track. MedPlay will notify you and resume it automatically when available again.",
            )
            textSize = 12f
        }

        val qualityLabel = TextView(this).apply {
            text = tr("Calidad de grabación", "Recording quality")
            textSize = 14f
        }

        val qualityInfoView = TextView(this).apply {
            text = tr(
                "Detectando modos compatibles con este teléfono...",
                "Detecting modes supported by this phone...",
            )
            textSize = 13f
        }

        val qualitySpinner = Spinner(this)
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(qualityLabel(RecordingQualityPreference.Mode.AUTO)),
        )

        val startButton = Button(this).apply {
            text = tr("Iniciar grabación", "Start recording")
            setOnClickListener {
                requestOverlayPermissionThenCapture()
            }
        }

        val clipLengthLabel = TextView(this).apply {
            text = tr("Duración del clip", "Clip duration")
            textSize = 14f
        }

        val clipLengthOptions = ClipLengthPreference.allowedSeconds
        val clipLengthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                clipLengthOptions.map(::durationLabel),
            )
            val current = ClipLengthPreference.get(this@MainActivity)
            setSelection(clipLengthOptions.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    ClipLengthPreference.set(
                        this@MainActivity,
                        clipLengthOptions[position],
                    )
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val saveClipButton = Button(this).apply {
            text = tr("Guardar clip", "Save clip")
            setOnClickListener {
                val seconds = ClipLengthPreference.get(this@MainActivity)
                startService(
                    CaptureService.createSaveIntent(
                        this@MainActivity,
                        seconds,
                    )
                )
                statusView.text = tr(
                    "Guardando los últimos ${durationLabel(seconds)}...",
                    "Saving the previous ${durationLabel(seconds)}...",
                )
            }
        }

        fun refreshRecordingModeUi(mode: RecordingModePreference.Mode) {
            val fullSession =
                mode == RecordingModePreference.Mode.FULL_SESSION
            microphoneVoiceToggle.visibility =
                if (fullSession) View.VISIBLE else View.GONE
            microphoneVoiceInfo.visibility =
                if (fullSession) View.VISIBLE else View.GONE
            clipLengthLabel.visibility =
                if (fullSession) View.GONE else View.VISIBLE
            clipLengthSpinner.visibility =
                if (fullSession) View.GONE else View.VISIBLE
            saveClipButton.visibility =
                if (fullSession) View.GONE else View.VISIBLE
            startButton.text = if (fullSession) {
                tr(
                    "Iniciar grabación completa",
                    "Start full-session recording",
                )
            } else {
                tr(
                    "Iniciar grabación de clips",
                    "Start clip recording",
                )
            }
        }

        recordingModeSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    val mode = recordingModes[position]
                    RecordingModePreference.set(this@MainActivity, mode)
                    refreshRecordingModeUi(mode)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        refreshRecordingModeUi(
            RecordingModePreference.get(this@MainActivity)
        )

        val clipLibraryLabel = TextView(this).apply {
            text = tr("Mis clips", "My clips")
            textSize = 16f
        }

        val refreshClipLibraryButton = Button(this).apply {
            text = tr("Actualizar clips", "Refresh clips")
            setOnClickListener {
                refreshClipLibrary()
            }
        }

        clipLibraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val floatingStyleLabel = TextView(this).apply {
            text = tr("Botón flotante", "Floating button")
            textSize = 14f
        }

        val initialFloatingStyle =
            FloatingControlStylePreference.get(this@MainActivity)
        val density = resources.displayMetrics.density

        val floatingPreviewLabel = TextView(this).apply {
            text = tr("Vista previa", "Preview")
            textSize = 13f
        }
        val floatingPreview = GlassClipView(this).apply {
            setStyle(initialFloatingStyle)
            isClickable = false
        }
        val previewContainer = FrameLayout(this).apply {
            val previewSizePx = (initialFloatingStyle.sizeDp * density).toInt()
            addView(
                floatingPreview,
                FrameLayout.LayoutParams(
                    previewSizePx,
                    previewSizePx,
                    Gravity.CENTER,
                )
            )
            minimumHeight = (112f * density).toInt()
        }

        fun refreshFloatingPreview() {
            val style = FloatingControlStylePreference.get(this@MainActivity)
            floatingPreview.setStyle(style)
            val sizePx = (style.sizeDp * density).toInt()
            floatingPreview.layoutParams = FrameLayout.LayoutParams(
                sizePx,
                sizePx,
                Gravity.CENTER,
            )
            floatingPreview.requestLayout()
        }

        val floatingTintOptions = FloatingControlStylePreference.Tint.entries
        val floatingTintSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                floatingTintOptions.map(::floatingTintLabel),
            )
            setSelection(
                floatingTintOptions.indexOf(initialFloatingStyle.tint)
                    .coerceAtLeast(0)
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    FloatingControlStylePreference.setTint(
                        this@MainActivity,
                        floatingTintOptions[position],
                    )
                    refreshFloatingPreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val floatingSizeLabel = TextView(this).apply {
            text = tr(
                "Tamaño: ${initialFloatingStyle.sizeDp}",
                "Size: ${initialFloatingStyle.sizeDp}",
            )
            textSize = 13f
        }
        val floatingSizeSeekBar = SeekBar(this).apply {
            max = FloatingControlStylePreference.MAX_SIZE_DP -
                FloatingControlStylePreference.MIN_SIZE_DP
            progress = initialFloatingStyle.sizeDp -
                FloatingControlStylePreference.MIN_SIZE_DP
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val value =
                            FloatingControlStylePreference.MIN_SIZE_DP + progress
                        floatingSizeLabel.text = tr(
                            "Tamaño: $value",
                            "Size: $value",
                        )
                        if (fromUser) {
                            FloatingControlStylePreference.setSizeDp(
                                this@MainActivity,
                                value,
                            )
                            refreshFloatingPreview()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                }
            )
        }

        val floatingOpacityLabel = TextView(this).apply {
            text = tr(
                "Transparencia: ${initialFloatingStyle.opacityPercent}%",
                "Opacity: ${initialFloatingStyle.opacityPercent}%",
            )
            textSize = 13f
        }
        val floatingOpacitySeekBar = SeekBar(this).apply {
            max = FloatingControlStylePreference.MAX_OPACITY_PERCENT -
                FloatingControlStylePreference.MIN_OPACITY_PERCENT
            progress = initialFloatingStyle.opacityPercent -
                FloatingControlStylePreference.MIN_OPACITY_PERCENT
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val value =
                            FloatingControlStylePreference.MIN_OPACITY_PERCENT +
                                progress
                        floatingOpacityLabel.text = tr(
                            "Transparencia: $value%",
                            "Opacity: $value%",
                        )
                        if (fromUser) {
                            FloatingControlStylePreference.setOpacityPercent(
                                this@MainActivity,
                                value,
                            )
                            refreshFloatingPreview()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                }
            )
        }

        val floatingStyleHint = TextView(this).apply {
            text = tr(
                "El botón aparece automáticamente al comenzar la grabación y confirma visualmente cuando guarda un clip.",
                "The button appears automatically when capture starts and visually confirms when a clip is saved.",
            )
            textSize = 12f
        }

        val stopButton = Button(this).apply {
            text = tr("Detener grabación", "Stop recording")
            setOnClickListener {
                startService(CaptureService.createStopIntent(this@MainActivity))
                statusView.text = tr(
                    "Deteniendo grabación...",
                    "Stopping recording...",
                )
            }
        }

        val diagnosticsButton = Button(this).apply {
            text = tr(
                "Mostrar diagnóstico de la última grabación",
                "Show last recording diagnostics",
            )
            setOnClickListener {
                reportView.text =
                    CaptureService.readLastSessionDiagnostics(this@MainActivity)
                        ?: tr(
                            "Todavía no hay un diagnóstico de una grabación terminada.",
                            "No completed recording diagnostics available yet.",
                        )
            }
        }

        val copyDiagnosticsButton = Button(this).apply {
            text = tr(
                "Copiar diagnóstico",
                "Copy diagnostics",
            )
            setOnClickListener {
                val diagnostics =
                    CaptureService.readLastSessionDiagnostics(this@MainActivity)
                if (diagnostics == null) {
                    statusView.text = tr(
                        "Todavía no hay un diagnóstico para copiar.",
                        "No completed recording diagnostics available yet.",
                    )
                } else {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "MedPlay recording diagnostics",
                            diagnostics,
                        )
                    )
                    statusView.text = tr(
                        "Diagnóstico copiado.",
                        "Recording diagnostics copied.",
                    )
                }
            }
        }

        val advancedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(stopButton)
            addView(diagnosticsButton)
            addView(copyDiagnosticsButton)
            addView(reportView)
        }

        val advancedButton = Button(this).apply {
            text = tr("Opciones avanzadas ▾", "Advanced options ▾")
            setOnClickListener {
                val opening = advancedContainer.visibility != View.VISIBLE
                advancedContainer.visibility = if (opening) View.VISIBLE else View.GONE
                text = if (opening) {
                    tr("Opciones avanzadas ▴", "Advanced options ▴")
                } else {
                    tr("Opciones avanzadas ▾", "Advanced options ▾")
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)

            addView(languageLabel)
            addView(languageSpinner)
            addView(statusView)

            addView(qualityLabel)
            addView(qualitySpinner)
            addView(qualityInfoView)
            addView(captureChoiceInfoView)

            addView(recordingModeLabel)
            addView(recordingModeSpinner)
            addView(internalAudioToggle)
            addView(microphoneVoiceToggle)
            addView(microphoneVoiceInfo)
            addView(startButton)

            addView(clipLengthLabel)
            addView(clipLengthSpinner)
            addView(saveClipButton)
            addView(clipLibraryLabel)
            addView(refreshClipLibraryButton)
            addView(clipLibraryContainer)

            addView(floatingStyleLabel)
            addView(floatingPreviewLabel)
            addView(previewContainer)
            addView(floatingTintSpinner)
            addView(floatingSizeLabel)
            addView(floatingSizeSeekBar)
            addView(floatingOpacityLabel)
            addView(floatingOpacitySeekBar)
            addView(floatingStyleHint)
            addView(advancedButton)
            addView(advancedContainer)
        }

        val rootScrollView = ScrollView(this).apply {
            isFillViewport = true
            isSaveEnabled = false
            clipToPadding = false
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val leftInset: Int
                val topInset: Int
                val rightInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() or
                            WindowInsets.Type.displayCutout()
                    )
                    leftInset = safeInsets.left
                    topInset = safeInsets.top
                    rightInset = safeInsets.right
                    bottomInset = safeInsets.bottom
                } else {
                    @Suppress("DEPRECATION")
                    leftInset = maxOf(
                        insets.systemWindowInsetLeft,
                        insets.displayCutout?.safeInsetLeft ?: 0,
                    )
                    @Suppress("DEPRECATION")
                    topInset = maxOf(
                        insets.systemWindowInsetTop,
                        insets.displayCutout?.safeInsetTop ?: 0,
                    )
                    @Suppress("DEPRECATION")
                    rightInset = maxOf(
                        insets.systemWindowInsetRight,
                        insets.displayCutout?.safeInsetRight ?: 0,
                    )
                    @Suppress("DEPRECATION")
                    bottomInset = maxOf(
                        insets.systemWindowInsetBottom,
                        insets.displayCutout?.safeInsetBottom ?: 0,
                    )
                }
                view.setPadding(leftInset, topInset, rightInset, bottomInset)
                insets
            }
        }
        setContentView(rootScrollView)
        refreshClipLibrary()
        rootScrollView.requestApplyInsets()
        rootScrollView.post {
            rootScrollView.scrollTo(0, 0)
            rootScrollView.fullScroll(View.FOCUS_UP)
        }

        thread(name = "medplay-codec-probe") {
            val supportedModes = runCatching {
                EncoderCapabilityProbe.supportedQualityModes()
            }.getOrElse {
                listOf(RecordingQualityPreference.Mode.AUTO)
            }
            val autoProfile = runCatching {
                EncoderCapabilityProbe.chooseSafeStartupProfile(
                    RecordingQualityPreference.Mode.AUTO
                )
            }.getOrNull()

            runOnUiThread {
                val storedMode = RecordingQualityPreference.get(this)
                val selectedMode = if (storedMode in supportedModes) {
                    storedMode
                } else {
                    RecordingQualityPreference.Mode.AUTO.also {
                        RecordingQualityPreference.set(this, it)
                    }
                }

                qualitySpinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    supportedModes.map(::qualityLabel),
                )
                qualitySpinner.setSelection(
                    supportedModes.indexOf(selectedMode).coerceAtLeast(0)
                )
                qualitySpinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long,
                        ) {
                            RecordingQualityPreference.set(
                                this@MainActivity,
                                supportedModes[position],
                            )
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }

                qualityInfoView.text = if (autoProfile == null) {
                    tr(
                        "No se encontró un perfil automático de hardware compatible.",
                        "No validated hardware automatic profile was found.",
                    )
                } else {
                    tr(
                        "Automático recomendado: ${autoProfile.width}x${autoProfile.height}, " +
                            "${autoProfile.fps} FPS, ${autoProfile.codec.name}, " +
                            "${autoProfile.bitrate / 1_000_000} Mbps.",
                        "Recommended automatic mode: ${autoProfile.width}x${autoProfile.height}, " +
                            "${autoProfile.fps} FPS, ${autoProfile.codec.name}, " +
                            "${autoProfile.bitrate / 1_000_000} Mbps.",
                    )
                }
            }
        }
    }

    private fun refreshClipLibrary() {
        if (!::clipLibraryContainer.isInitialized) return

        clipLibraryContainer.removeAllViews()
        val clips = ClipLibrary.listExisting(this)
            .sortedByDescending { it.createdAtMs }

        if (clips.isEmpty()) {
            clipLibraryContainer.addView(
                TextView(this).apply {
                    text = tr(
                        "Todavía no hay videos. Tus grabaciones aparecerán aquí con una vista previa.",
                        "No videos yet. Your recordings will appear here with a preview.",
                    )
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(
                        0,
                        MediaUiStyle.dp(this@MainActivity, 12),
                        0,
                        MediaUiStyle.dp(this@MainActivity, 20),
                    )
                }
            )
            return
        }

        clips.forEach { clip ->
            val durationSeconds =
                (clip.durationUs / 1_000_000L).coerceAtLeast(0L)
            val capturedAt = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(Date(clip.createdAtMs))
            val audioLabel = when {
                clip.hasGameAudio && clip.hasMicrophone ->
                    tr("Juego + voz", "Game + voice")
                clip.hasGameAudio ->
                    tr("Audio del juego", "Game audio")
                clip.hasMicrophone ->
                    tr("Voz", "Voice")
                else ->
                    tr("Sin audio", "No audio")
            }

            fun openPlayer() {
                startActivity(
                    Intent(
                        this@MainActivity,
                        ClipPlayerActivity::class.java,
                    ).apply {
                        data = clip.uri
                    }
                )
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    MediaUiStyle.dp(this@MainActivity, 10),
                    MediaUiStyle.dp(this@MainActivity, 10),
                    MediaUiStyle.dp(this@MainActivity, 10),
                    MediaUiStyle.dp(this@MainActivity, 10),
                )
                background = MediaUiStyle.rounded(
                    MediaUiStyle.SURFACE,
                    18,
                    this@MainActivity,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { openPlayer() }
            }

            val thumbnailFrame = FrameLayout(this).apply {
                background = MediaUiStyle.rounded(
                    Color.BLACK,
                    14,
                    this@MainActivity,
                )
                clipToOutline = true
            }

            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
                contentDescription = tr(
                    "Vista previa del video",
                    "Video preview",
                )
            }
            thumbnailFrame.addView(
                thumbnail,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )

            val durationBadge = TextView(this).apply {
                text = formatClipDuration(durationSeconds)
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(
                    MediaUiStyle.dp(this@MainActivity, 8),
                    MediaUiStyle.dp(this@MainActivity, 4),
                    MediaUiStyle.dp(this@MainActivity, 8),
                    MediaUiStyle.dp(this@MainActivity, 4),
                )
                background = MediaUiStyle.rounded(
                    0xCC000000.toInt(),
                    8,
                    this@MainActivity,
                )
            }
            thumbnailFrame.addView(
                durationBadge,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.BOTTOM,
                ).apply {
                    marginEnd = MediaUiStyle.dp(this@MainActivity, 8)
                    bottomMargin = MediaUiStyle.dp(this@MainActivity, 8)
                }
            )

            card.addView(
                thumbnailFrame,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    MediaUiStyle.dp(this@MainActivity, 190),
                )
            )

            val title = TextView(this).apply {
                text = capturedAt
                setTextColor(MediaUiStyle.PRIMARY_TEXT)
                textSize = 15f
                setPadding(
                    0,
                    MediaUiStyle.dp(this@MainActivity, 10),
                    0,
                    0,
                )
            }
            val metadata = TextView(this).apply {
                text = audioLabel
                setTextColor(MediaUiStyle.SECONDARY_TEXT)
                textSize = 12f
            }
            card.addView(title)
            card.addView(metadata)

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(
                    0,
                    MediaUiStyle.dp(this@MainActivity, 8),
                    0,
                    0,
                )
            }

            actions.addView(
                TextView(this).apply {
                    text = tr("Editar", "Edit")
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(
                        MediaUiStyle.dp(this@MainActivity, 16),
                        MediaUiStyle.dp(this@MainActivity, 9),
                        MediaUiStyle.dp(this@MainActivity, 16),
                        MediaUiStyle.dp(this@MainActivity, 9),
                    )
                    background = MediaUiStyle.rounded(
                        MediaUiStyle.SURFACE_ELEVATED,
                        12,
                        this@MainActivity,
                    )
                    setOnClickListener {
                        startActivity(
                            Intent(
                                this@MainActivity,
                                ClipEditorActivity::class.java,
                            ).apply {
                                data = clip.uri
                            }
                        )
                    }
                }
            )

            actions.addView(
                TextView(this).apply {
                    text = tr("Borrar", "Delete")
                    setTextColor(MediaUiStyle.DANGER)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(
                        MediaUiStyle.dp(this@MainActivity, 16),
                        MediaUiStyle.dp(this@MainActivity, 9),
                        MediaUiStyle.dp(this@MainActivity, 16),
                        MediaUiStyle.dp(this@MainActivity, 9),
                    )
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(
                                tr(
                                    "¿Borrar este video?",
                                    "Delete this video?",
                                )
                            )
                            .setMessage(
                                tr(
                                    "Se eliminará de MedPlay y de la Galería. Esta acción no se puede deshacer.",
                                    "It will be removed from MedPlay and the Gallery. This action cannot be undone.",
                                )
                            )
                            .setNegativeButton(
                                tr("Cancelar", "Cancel"),
                                null,
                            )
                            .setPositiveButton(
                                tr("Borrar", "Delete"),
                            ) { _, _ ->
                                val deleted = ClipLibrary.delete(
                                    this@MainActivity,
                                    clip.uri,
                                )
                                statusView.text = if (deleted) {
                                    tr(
                                        "Video borrado.",
                                        "Video deleted.",
                                    )
                                } else {
                                    tr(
                                        "No se pudo borrar el video.",
                                        "The video could not be deleted.",
                                    )
                                }
                                refreshClipLibrary()
                            }
                            .show()
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = MediaUiStyle.dp(this@MainActivity, 8)
                }
            )

            card.addView(actions)
            clipLibraryContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = MediaUiStyle.dp(this@MainActivity, 10)
                }
            )

            thread(name = "medplay-clip-thumbnail") {
                val bitmap = runCatching {
                    contentResolver.loadThumbnail(
                        clip.uri,
                        Size(640, 360),
                        null,
                    )
                }.getOrNull()

                if (bitmap != null) {
                    runOnUiThread {
                        thumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun formatClipDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun floatingTintLabel(
        tint: FloatingControlStylePreference.Tint,
    ): String {
        return when (tint) {
            FloatingControlStylePreference.Tint.ICE ->
                tr("Cristal hielo", "Ice glass")
            FloatingControlStylePreference.Tint.SKY ->
                tr("Cristal azul", "Sky glass")
            FloatingControlStylePreference.Tint.VIOLET ->
                tr("Cristal violeta", "Violet glass")
            FloatingControlStylePreference.Tint.EMERALD ->
                tr("Cristal esmeralda", "Emerald glass")
            FloatingControlStylePreference.Tint.AMBER ->
                tr("Cristal ámbar", "Amber glass")
        }
    }

    private fun qualityLabel(mode: RecordingQualityPreference.Mode): String {
        return when (mode) {
            RecordingQualityPreference.Mode.AUTO ->
                tr("Automático (recomendado)", "Automatic (recommended)")
            RecordingQualityPreference.Mode.P720_30 -> "720p • 30 FPS"
            RecordingQualityPreference.Mode.P720_60 -> "720p • 60 FPS"
            RecordingQualityPreference.Mode.P1080_30 -> "1080p • 30 FPS"
            RecordingQualityPreference.Mode.P1080_60 -> "1080p • 60 FPS"
            RecordingQualityPreference.Mode.P1440_30 -> "1440p • 30 FPS"
            RecordingQualityPreference.Mode.P1440_60 -> "1440p • 60 FPS"
        }
    }

    private fun durationLabel(seconds: Int): String {
        return if (
            AppLanguagePreference.get(this) ==
            AppLanguagePreference.Language.SPANISH
        ) {
            when {
                seconds < 60 -> "$seconds segundos"
                seconds == 60 -> "1 minuto"
                else -> "${seconds / 60} minutos"
            }
        } else {
            when {
                seconds < 60 -> "$seconds seconds"
                seconds == 60 -> "1 minute"
                else -> "${seconds / 60} minutes"
            }
        }
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(this, spanish, english)
    }

    private fun requestOverlayPermissionThenCapture() {
        if (Settings.canDrawOverlays(this)) {
            requestNotificationPermissionThenCapture()
            return
        }

        pendingCaptureAfterOverlayPermission = true
        statusView.text = tr(
            "Concede el permiso del botón flotante. Al volver, MedPlay continuará automáticamente.",
            "Grant the floating-button permission. When you return, MedPlay will continue automatically.",
        )
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    override fun onResume() {
        super.onResume()
        refreshClipLibrary()
        if (
            pendingCaptureAfterOverlayPermission &&
            Settings.canDrawOverlays(this)
        ) {
            pendingCaptureAfterOverlayPermission = false
            requestNotificationPermissionThenCapture()
        }
    }

    private fun requestNotificationPermissionThenCapture() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS,
            )
            return
        }

        requestCaptureWithOptionalInternalAudio()
    }

    private fun requestCaptureWithOptionalInternalAudio() {
        val fullSession =
            RecordingModePreference.get(this) ==
                RecordingModePreference.Mode.FULL_SESSION
        val needsAudioPermission =
            InternalAudioPreference.isEnabled(this) ||
                (
                    fullSession &&
                        MicrophoneVoicePreference.isEnabled(this)
                )

        if (!needsAudioPermission) {
            statusView.text = tr(
                "Audio interno y voz desactivados; se grabará solo video.",
                "Internal audio and voice are disabled; continuing video-only.",
            )
            launchMediaProjectionConsent()
            return
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchMediaProjectionConsent()
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO,
        )
    }

    private fun launchMediaProjectionConsent() {
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            mediaProjectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }

        startActivityForResult(
            captureIntent,
            REQUEST_MEDIA_PROJECTION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_POST_NOTIFICATIONS -> {
                val granted =
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                statusView.text = if (granted) {
                    tr(
                        "Permiso de notificaciones concedido.",
                        "Notification permission granted.",
                    )
                } else {
                    tr(
                        "Sin notificaciones: la grabación puede continuar, pero los controles de notificación pueden quedar ocultos.",
                        "Notification permission denied; capture can continue, but notification controls may be hidden.",
                    )
                }
                requestCaptureWithOptionalInternalAudio()
            }

            REQUEST_RECORD_AUDIO -> {
                val granted =
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                statusView.text = if (granted) {
                    tr(
                        "Permiso de audio concedido.",
                        "Audio permission granted.",
                    )
                } else {
                    tr(
                        "Permiso de audio rechazado; el video continuará sin audio interno ni tu voz.",
                        "Audio permission denied; video will continue without internal audio or your voice.",
                    )
                }
                launchMediaProjectionConsent()
            }
        }
    }

    @Deprecated("Deprecated in Android API, kept here to avoid extra UI dependencies in the prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            statusView.text = tr(
                "Permiso de captura de pantalla rechazado.",
                "Screen capture permission denied.",
            )
            return
        }

        val serviceIntent = CaptureService.createStartIntent(
            context = this,
            resultCode = resultCode,
            resultData = data,
        )
        startCaptureServiceSafely(serviceIntent)
    }

    private fun startCaptureServiceSafely(
        serviceIntent: Intent,
        attempt: Int = 0,
    ) {
        val startResult = runCatching {
            startForegroundService(serviceIntent)
        }
        val started = startResult.isSuccess

        if (started) {
            statusView.text = tr(
                "Grabación iniciándose.",
                "Recording is starting.",
            )
            return
        }

        if (attempt < 1) {
            statusView.text = tr(
                "Android retrasó el inicio de MedPlay; reintentando...",
                "Android delayed MedPlay startup; retrying...",
            )
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    startCaptureServiceSafely(
                        serviceIntent,
                        attempt + 1,
                    )
                },
                350L,
            )
            return
        }

        CaptureService.recordStartupFailure(
            this,
            "activity_start_foreground_service",
            startResult.exceptionOrNull(),
        )
        statusView.text = tr(
            "Android no permitió iniciar la grabación. MedPlay siguió abierto; vuelve a intentar Iniciar grabación.",
            "Android did not allow capture to start. MedPlay stayed open; try Start recording again.",
        )
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_RECORD_AUDIO = 2002
        private const val REQUEST_POST_NOTIFICATIONS = 2003
    }
}
