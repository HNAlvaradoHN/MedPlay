package com.medplay.app.recording

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class FloatingSessionControl(
    private val context: Context,
    private val elapsedMsProvider: () -> Long,
    private val microphoneEnabledProvider: () -> Boolean,
    private val onToggleMicrophone: () -> Boolean,
    private val onTogglePause: () -> Boolean,
    private val onStop: () -> Unit,
) : AutoCloseable {

    private enum class Side {
        LEFT,
        RIGHT,
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var timerView: TextView? = null
    private var microphoneButton: Button? = null
    private var pauseButton: Button? = null
    private var statusView: TextView? = null
    private var statusParams: WindowManager.LayoutParams? = null
    private var clearStatus: Runnable? = null
    private var side = Side.RIGHT
    private var paused = false

    private val timerTick = object : Runnable {
        override fun run() {
            timerView?.text = formatElapsed(elapsedMsProvider())
            if (bubble != null) {
                handler.postDelayed(this, TIMER_REFRESH_MS)
            }
        }
    }

    fun show(): Boolean {
        if (bubble != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val sizePx = (56f * density).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val initialX = (screenWidth - sizePx)
            .coerceAtLeast(0)

        val bubbleView = TextView(context).apply {
            text = "●"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(238, 245, 255))
            background = roundedBackground(
                color = Color.argb(205, 24, 28, 36),
                radiusDp = 28f,
            )
            elevation = 14f * density
        }
        val layout = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = (190f * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - downX).toInt()
                    layout.y = (startY + (event.rawY - downY).toInt())
                        .coerceAtLeast(0)
                    runCatching {
                        windowManager.updateViewLayout(bubbleView, layout)
                    }
                    updatePanelPosition()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved =
                        abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved <= 12f * density) {
                        togglePanel()
                    } else {
                        snapToNearestSide()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        val shown = runCatching {
            windowManager.addView(bubbleView, layout)
            true
        }.getOrDefault(false)
        if (!shown) return false

        bubble = bubbleView
        bubbleParams = layout
        handler.post(timerTick)
        return true
    }

    fun showStatus(message: String) {
        if (!Settings.canDrawOverlays(context)) return

        clearStatus?.let(handler::removeCallbacks)
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
        statusParams = null

        val view = TextView(context).apply {
            text = message
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(
                (12f * density).toInt(),
                (8f * density).toInt(),
                (12f * density).toInt(),
                (8f * density).toInt(),
            )
            background = roundedBackground(
                Color.argb(225, 18, 20, 26),
                14f,
            )
        }

        val bubbleLayout = bubbleParams ?: return
        val params = WindowManager.LayoutParams(
            (260f * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = statusX(bubbleLayout)
            y = bubbleLayout.y + bubbleLayout.height + (8f * density).toInt()
        }

        if (runCatching { windowManager.addView(view, params) }.isFailure) return

        statusView = view
        statusParams = params
        val clear = Runnable {
            if (statusView === view) {
                runCatching { windowManager.removeView(view) }
                statusView = null
                statusParams = null
            }
        }
        clearStatus = clear
        handler.postDelayed(clear, STATUS_DURATION_MS)
    }

    private fun togglePanel() {
        if (panel != null) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        val bubbleLayout = bubbleParams ?: return
        val widthPx = (220f * density).toInt()

        val timer = TextView(context).apply {
            text = formatElapsed(elapsedMsProvider())
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        val mic = Button(context).apply {
            setOnClickListener {
                val enabled = onToggleMicrophone()
                updateMicrophoneLabel(enabled)
            }
        }
        val pause = Button(context).apply {
            setOnClickListener {
                paused = onTogglePause()
                updatePauseLabel()
            }
        }
        val stop = Button(context).apply {
            text = tr("Detener y guardar", "Stop & save")
            setOnClickListener {
                hidePanel()
                onStop()
            }
        }

        microphoneButton = mic
        pauseButton = pause
        timerView = timer
        updateMicrophoneLabel(microphoneEnabledProvider())
        updatePauseLabel()

        val panelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10f * density).toInt(),
                (10f * density).toInt(),
                (10f * density).toInt(),
                (10f * density).toInt(),
            )
            background = roundedBackground(
                Color.argb(225, 20, 23, 30),
                18f,
            )
            elevation = 16f * density
            addView(timer)
            addView(mic)
            addView(pause)
            addView(stop)
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX(bubbleLayout, widthPx)
            y = bubbleLayout.y
        }

        if (runCatching { windowManager.addView(panelView, params) }.isSuccess) {
            panel = panelView
            panelParams = params
        }
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
        timerView = null
        microphoneButton = null
        pauseButton = null
    }

    private fun snapToNearestSide() {
        val layout = bubbleParams ?: return
        val view = bubble ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val center = layout.x + layout.width / 2
        side = if (center < screenWidth / 2) Side.LEFT else Side.RIGHT
        layout.x = if (side == Side.LEFT) {
            0
        } else {
            (screenWidth - layout.width).coerceAtLeast(0)
        }
        runCatching { windowManager.updateViewLayout(view, layout) }
        updatePanelPosition()
    }

    private fun updatePanelPosition() {
        val bubbleLayout = bubbleParams ?: return
        panelParams?.let { params ->
            params.x = panelX(bubbleLayout, params.width)
            params.y = bubbleLayout.y
            panel?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
        statusParams?.let { params ->
            params.x = statusX(bubbleLayout)
            params.y =
                bubbleLayout.y + bubbleLayout.height + (8f * density).toInt()
            statusView?.let {
                runCatching { windowManager.updateViewLayout(it, params) }
            }
        }
    }

    private fun panelX(
        bubbleLayout: WindowManager.LayoutParams,
        panelWidth: Int,
    ): Int {
        val margin = (8f * density).toInt()
        return if (side == Side.LEFT) {
            bubbleLayout.x + bubbleLayout.width + margin
        } else {
            (bubbleLayout.x - panelWidth - margin).coerceAtLeast(0)
        }
    }

    private fun statusX(bubbleLayout: WindowManager.LayoutParams): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (260f * density).toInt()
        return if (side == Side.LEFT) {
            bubbleLayout.x
        } else {
            (screenWidth - width - 8f * density).toInt().coerceAtLeast(0)
        }
    }

    private fun updateMicrophoneLabel(enabled: Boolean) {
        microphoneButton?.text = if (enabled) {
            tr("Micrófono: ON", "Microphone: ON")
        } else {
            tr("Micrófono: OFF", "Microphone: OFF")
        }
    }

    private fun updatePauseLabel() {
        pauseButton?.text = if (paused) {
            tr("Reanudar", "Resume")
        } else {
            tr("Pausar", "Pause")
        }
    }

    private fun formatElapsed(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                Color.argb(150, 210, 225, 255),
            )
        }
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(context, spanish, english)
    }

    override fun close() {
        handler.removeCallbacks(timerTick)
        clearStatus?.let(handler::removeCallbacks)
        clearStatus = null
        hidePanel()

        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
        statusParams = null

        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    companion object {
        private const val TIMER_REFRESH_MS = 500L
        private const val STATUS_DURATION_MS = 3_500L
    }
}
