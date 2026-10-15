package com.medplay.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.medplay.app.recording.AppLanguagePreference
import com.medplay.app.recording.CaptureScopePreference
import com.medplay.app.recording.CaptureService
import com.medplay.app.recording.ClipLengthPreference
import com.medplay.app.recording.EncoderCapabilityProbe
import com.medplay.app.recording.FloatingControlStylePreference
import com.medplay.app.recording.InternalAudioPreference
import com.medplay.app.recording.RecordingQualityPreference
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

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

        val captureScopeLabel = TextView(this).apply {
            text = tr("Qué grabar", "What to capture")
            textSize = 14f
        }

        val captureScopeOptions = if (Build.VERSION.SDK_INT >= 34) {
            listOf(
                CaptureScopePreference.Mode.APP_ONLY,
                CaptureScopePreference.Mode.FULL_SCREEN,
            )
        } else {
            listOf(CaptureScopePreference.Mode.FULL_SCREEN)
        }
        val captureScopeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                captureScopeOptions.map(::captureScopeLabel),
            )
            val stored = CaptureScopePreference.get(this@MainActivity)
            setSelection(
                captureScopeOptions.indexOf(stored)
                    .takeIf { it >= 0 }
                    ?: 0
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    CaptureScopePreference.set(
                        this@MainActivity,
                        captureScopeOptions[position],
                    )
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val captureScopeInfoView = TextView(this).apply {
            text = if (Build.VERSION.SDK_INT >= 34) {
                tr(
                    "Solo juego es recomendado: Android te pedirá elegir la app del juego y excluirá notificaciones y otros elementos del sistema.",
                    "Game only is recommended: Android will ask you to choose the game app and excludes notifications and other system UI.",
                )
            } else {
                tr(
                    "Este Android no admite captura de una sola app; se usará pantalla completa.",
                    "This Android version does not support single-app capture; full screen will be used.",
                )
            }
            textSize = 13f
        }

        val internalAudioToggle = CheckBox(this).apply {
            text = tr("Grabar audio interno", "Capture internal audio")
            isChecked = InternalAudioPreference.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                InternalAudioPreference.setEnabled(this@MainActivity, enabled)
            }
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
                requestNotificationPermissionThenCapture()
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

        val floatingStyleLabel = TextView(this).apply {
            text = tr("Botón flotante", "Floating button")
            textSize = 14f
        }

        val floatingTintOptions = FloatingControlStylePreference.Tint.entries
        val floatingTintSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                floatingTintOptions.map(::floatingTintLabel),
            )
            val style = FloatingControlStylePreference.get(this@MainActivity)
            setSelection(
                floatingTintOptions.indexOf(style.tint).coerceAtLeast(0)
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
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val initialFloatingStyle =
            FloatingControlStylePreference.get(this@MainActivity)

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
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                }
            )
        }

        val floatingStyleHint = TextView(this).apply {
            text = tr(
                "Estilo glass translúcido. Los cambios se aplican al activar o reactivar el botón.",
                "Translucent glass style. Changes apply when the button is enabled or refreshed.",
            )
            textSize = 12f
        }

        val overlayButton = Button(this).apply {
            text = tr(
                "Activar botón flotante",
                "Enable floating clip button",
            )
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(
                        CaptureService.createShowOverlayIntent(this@MainActivity)
                    )
                    statusView.text = tr(
                        "Botón flotante activado.",
                        "Floating clip button enabled.",
                    )
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                    statusView.text = tr(
                        "Concede el permiso para mostrar sobre otras apps y vuelve a tocar este botón.",
                        "Grant overlay permission, then tap this button again.",
                    )
                }
            }
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)

            addView(languageLabel)
            addView(languageSpinner)
            addView(statusView)

            addView(qualityLabel)
            addView(qualitySpinner)
            addView(qualityInfoView)

            addView(captureScopeLabel)
            addView(captureScopeSpinner)
            addView(captureScopeInfoView)

            addView(internalAudioToggle)
            addView(startButton)

            addView(clipLengthLabel)
            addView(clipLengthSpinner)
            addView(saveClipButton)

            addView(floatingStyleLabel)
            addView(floatingTintSpinner)
            addView(floatingSizeLabel)
            addView(floatingSizeSeekBar)
            addView(floatingOpacityLabel)
            addView(floatingOpacitySeekBar)
            addView(floatingStyleHint)
            addView(overlayButton)
            addView(stopButton)
            addView(diagnosticsButton)
            addView(copyDiagnosticsButton)
            addView(reportView)
        }

        val rootScrollView = ScrollView(this).apply {
            isFillViewport = true
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
        rootScrollView.requestApplyInsets()

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

    private fun captureScopeLabel(mode: CaptureScopePreference.Mode): String {
        return when (mode) {
            CaptureScopePreference.Mode.APP_ONLY ->
                tr("Solo juego (recomendado)", "Game only (recommended)")
            CaptureScopePreference.Mode.FULL_SCREEN ->
                tr("Pantalla completa", "Full screen")
        }
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
        if (!InternalAudioPreference.isEnabled(this)) {
            statusView.text = tr(
                "Audio interno desactivado; se grabará solo video.",
                "Internal audio disabled; continuing video-only.",
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
            val config = when (CaptureScopePreference.get(this)) {
                CaptureScopePreference.Mode.APP_ONLY ->
                    MediaProjectionConfig.createConfigForUserChoice()
                CaptureScopePreference.Mode.FULL_SCREEN ->
                    MediaProjectionConfig.createConfigForDefaultDisplay()
            }
            mediaProjectionManager.createScreenCaptureIntent(config)
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
                        "Permiso de audio interno concedido.",
                        "Internal audio permission granted.",
                    )
                } else {
                    tr(
                        "Audio interno no disponible; se grabará solo video.",
                        "Internal audio unavailable; continuing video-only.",
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
        startForegroundService(serviceIntent)
        statusView.text = tr(
            "Grabación iniciándose.",
            "Recording is starting.",
        )
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_RECORD_AUDIO = 2002
        private const val REQUEST_POST_NOTIFICATIONS = 2003
    }
}
