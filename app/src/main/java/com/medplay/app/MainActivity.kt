package com.medplay.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.medplay.app.recording.CaptureService
import com.medplay.app.recording.ClipLengthPreference
import com.medplay.app.recording.EncoderCapabilityProbe
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        statusView = TextView(this).apply {
            text = "Capture permission not granted."
            textSize = 16f
        }

        reportView = TextView(this).apply {
            text = "Detecting hardware video encoders..."
            textSize = 14f
        }

        val startButton = Button(this).apply {
            text = "Enable screen capture"
            setOnClickListener {
                requestCaptureWithOptionalInternalAudio()
            }
        }

        val save15Button = Button(this).apply {
            text = "Save previous 15 seconds"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 15))
                statusView.text = "Saving previous 15 seconds..."
            }
        }

        val save30Button = Button(this).apply {
            text = "Save previous 30 seconds"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 30))
                statusView.text = "Saving previous 30 seconds..."
            }
        }

        val save60Button = Button(this).apply {
            text = "Save previous 60 seconds"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 60))
                statusView.text = "Saving previous 60 seconds..."
            }
        }

        val clipLengthLabel = TextView(this).apply {
            text = "Floating clip length"
            textSize = 14f
        }

        val clipLengthOptions = ClipLengthPreference.allowedSeconds
        val clipLengthLabels = clipLengthOptions.map { seconds ->
            when {
                seconds < 60 -> "$seconds seconds"
                seconds == 60 -> "1 minute"
                else -> "${seconds / 60} minutes"
            }
        }
        val clipLengthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                clipLengthLabels,
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

        val overlayButton = Button(this).apply {
            text = "Enable floating clip button"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(CaptureService.createShowOverlayIntent(this@MainActivity))
                    statusView.text = "Floating clip button enabled."
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                    statusView.text = "Grant overlay permission, then tap enable again."
                }
            }
        }

        val save120Button = Button(this).apply {
            text = "Save previous 2 minutes"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 120))
            }
        }

        val save180Button = Button(this).apply {
            text = "Save previous 3 minutes"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 180))
            }
        }

        val save300Button = Button(this).apply {
            text = "Save previous 5 minutes"
            setOnClickListener {
                startService(CaptureService.createSaveIntent(this@MainActivity, 300))
            }
        }

        val diagnosticsButton = Button(this).apply {
            text = "Show last recording diagnostics"
            setOnClickListener {
                reportView.text =
                    CaptureService.readLastSessionDiagnostics(this@MainActivity)
                        ?: "No completed recording diagnostics available yet."
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop capture service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                statusView.text = "Capture service stopped."
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(statusView)
            addView(startButton)
            addView(save15Button)
            addView(save30Button)
            addView(save60Button)
            addView(save120Button)
            addView(save180Button)
            addView(save300Button)
            addView(clipLengthLabel)
            addView(clipLengthSpinner)
            addView(overlayButton)
            addView(stopButton)
            addView(diagnosticsButton)
            addView(reportView)
        }

        setContentView(
            ScrollView(this).apply {
                addView(content)
            }
        )

        thread(name = "medplay-codec-probe") {
            val report = runCatching { EncoderCapabilityProbe.buildTextReport() }
                .getOrElse { error ->
                    "Encoder probe failed: ${error.javaClass.simpleName}: ${error.message}"
                }

            runOnUiThread {
                reportView.text = report
            }
        }
    }

    private fun requestCaptureWithOptionalInternalAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchMediaProjectionConsent()
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO,
        )
    }

    private fun launchMediaProjectionConsent() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_RECORD_AUDIO) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        statusView.text = if (granted) {
            "Internal audio permission granted."
        } else {
            "Internal audio unavailable; continuing video-only."
        }
        launchMediaProjectionConsent()
    }

    @Deprecated("Deprecated in Android API, kept here to avoid extra UI dependencies in the prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            statusView.text = "Screen capture permission denied."
            return
        }

        val serviceIntent = CaptureService.createStartIntent(
            context = this,
            resultCode = resultCode,
            resultData = data,
        )
        startForegroundService(serviceIntent)
        statusView.text = "Capture permission granted; foreground service started."
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_RECORD_AUDIO = 2002
    }
}
