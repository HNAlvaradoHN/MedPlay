package com.medplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.medplay.app.recording.CaptureService
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
                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION,
                )
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
            addView(stopButton)
            addView(reportView)
        }

        setContentView(content)

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
    }
}
