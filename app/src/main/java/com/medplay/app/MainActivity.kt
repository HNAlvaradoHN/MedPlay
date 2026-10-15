package com.medplay.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.medplay.app.recording.EncoderCapabilityProbe
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reportView = TextView(this).apply {
            text = "Detecting hardware video encoders..."
            setPadding(32, 32, 32, 32)
            textSize = 14f
        }

        val scroll = ScrollView(this).apply {
            addView(
                reportView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        setContentView(scroll)

        thread(name = "medplay-codec-probe") {
            val report = runCatching { EncoderCapabilityProbe.buildTextReport() }
                .getOrElse { error -> "Encoder probe failed: ${error.javaClass.simpleName}: ${error.message}" }

            runOnUiThread {
                reportView.text = report
            }
        }
    }
}
