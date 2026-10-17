package com.medplay.app.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

class FloatingClipControl(
    private val context: Context,
    private val onSaveClip: () -> Boolean,
) : AutoCloseable {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())
    private var view: GlassClipView? = null
    private var params: WindowManager.LayoutParams? = null
    private var microphoneStatusView: TextView? = null
    private var microphoneStatusParams: WindowManager.LayoutParams? = null
    private var clearMicrophoneStatus: Runnable? = null

    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val density = context.resources.displayMetrics.density
        val style = FloatingControlStylePreference.get(context)
        val sizePx = (style.sizeDp * density).toInt()
        val exposedWidthPx = (sizePx * EDGE_EXPOSED_FRACTION).toInt()

        val bubble = GlassClipView(context).apply {
            setStyle(style)
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
            x = -(sizePx - exposedWidthPx)
            y = (180f * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    bubble.animatePress(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved > (12f * density)) {
                        bubble.animatePress(false)
                    }
                    layout.x = startX + (event.rawX - downX).toInt()
                    layout.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(bubble, layout) }
                    microphoneStatusParams?.let { statusLayout ->
                        statusLayout.x = layout.x
                        statusLayout.y = layout.y + layout.height +
                            (8f * density).toInt()
                        microphoneStatusView?.let { statusView ->
                            runCatching {
                                windowManager.updateViewLayout(statusView, statusLayout)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    bubble.animatePress(false)
                    val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved < (12f * density)) {
                        if (onSaveClip()) {
                            bubble.showAcceptedFeedback()
                        } else {
                            bubble.showBusyFeedback()
                        }
                    } else {
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        val centerX = layout.x + layout.width / 2
                        val exposedWidthPx =
                            (layout.width * EDGE_EXPOSED_FRACTION).toInt()
                        layout.x = if (centerX < screenWidth / 2) {
                            -(layout.width - exposedWidthPx)
                        } else {
                            (screenWidth - exposedWidthPx).coerceAtLeast(0)
                        }
                        runCatching {
                            windowManager.updateViewLayout(bubble, layout)
                        }
                        microphoneStatusParams?.let { statusLayout ->
                            statusLayout.x = layout.x
                            statusLayout.y = layout.y + layout.height +
                                (8f * density).toInt()
                            microphoneStatusView?.let { statusView ->
                                runCatching {
                                    windowManager.updateViewLayout(statusView, statusLayout)
                                }
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    bubble.animatePress(false)
                    true
                }

                else -> false
            }
        }

        return runCatching {
            windowManager.addView(bubble, layout)
            view = bubble
            params = layout
            true
        }.getOrDefault(false)
    }

    fun showSavedFeedback() {
        view?.showSavedFeedback()
    }

    fun showFailureFeedback() {
        view?.showFailureFeedback()
    }

    fun showMicrophoneBlocked(message: String) {
        showMicrophoneStatus(message, 4_500L)
    }

    fun showMicrophoneResumed(message: String) {
        showMicrophoneStatus(message, 2_500L)
    }

    private fun showMicrophoneStatus(message: String, durationMs: Long) {
        val bubbleLayout = params ?: return
        if (!Settings.canDrawOverlays(context)) return

        clearMicrophoneStatus?.let(handler::removeCallbacks)
        microphoneStatusView?.let { existing ->
            runCatching { windowManager.removeView(existing) }
        }
        microphoneStatusView = null
        microphoneStatusParams = null

        val density = context.resources.displayMetrics.density
        val statusView = TextView(context).apply {
            text = message
            textSize = 12f
            setTextColor(Color.WHITE)
            maxWidth = (280f * density).toInt()
            setPadding(
                (12f * density).toInt(),
                (8f * density).toInt(),
                (12f * density).toInt(),
                (8f * density).toInt(),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.argb(218, 20, 20, 24))
            }
            elevation = 14f * density
        }
        val statusLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleLayout.x
            y = bubbleLayout.y + bubbleLayout.height + (8f * density).toInt()
        }

        val shown = runCatching {
            windowManager.addView(statusView, statusLayout)
            true
        }.getOrDefault(false)
        if (!shown) return

        microphoneStatusView = statusView
        microphoneStatusParams = statusLayout
        val clear = Runnable {
            if (microphoneStatusView === statusView) {
                runCatching { windowManager.removeView(statusView) }
                microphoneStatusView = null
                microphoneStatusParams = null
            }
        }
        clearMicrophoneStatus = clear
        handler.postDelayed(clear, durationMs)
    }

    override fun close() {
        clearMicrophoneStatus?.let(handler::removeCallbacks)
        clearMicrophoneStatus = null
        microphoneStatusView?.let { status ->
            runCatching { windowManager.removeView(status) }
        }
        microphoneStatusView = null
        microphoneStatusParams = null

        view?.let { current ->
            runCatching { windowManager.removeView(current) }
        }
        view = null
        params = null
    }

    companion object {
        private const val EDGE_EXPOSED_FRACTION = 0.42f
    }
}

/**
 * Shared lightweight glass control renderer used by both the real floating
 * overlay and the in-app live preview.
 */
class GlassClipView(
    context: Context,
) : View(context) {

    private enum class Feedback {
        NORMAL,
        ACCEPTED,
        SAVED,
        BUSY,
        FAILED,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var style = FloatingControlStylePreference.Style(
        sizeDp = FloatingControlStylePreference.DEFAULT_SIZE_DP,
        opacityPercent = FloatingControlStylePreference.DEFAULT_OPACITY_PERCENT,
        tint = FloatingControlStylePreference.Tint.ICE,
    )
    private var feedback = Feedback.NORMAL
    private var clearFeedback: Runnable? = null

    init {
        isClickable = true
        isFocusable = false
        elevation = 12f * resources.displayMetrics.density
    }

    fun setStyle(value: FloatingControlStylePreference.Style) {
        style = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) return

        val opacity = style.opacityPercent / 100f
        val inset = diameter * 0.075f
        val radius = diameter / 2f - inset
        val centerX = width / 2f
        val centerY = height / 2f

        val start = withAlpha(
            style.tint.startColor,
            (255f * opacity).toInt(),
        )
        val end = withAlpha(
            style.tint.endColor,
            (255f * opacity * 0.78f).toInt(),
        )
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            start,
            end,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        borderPaint.shader = null
        borderPaint.strokeWidth = diameter * 0.035f
        borderPaint.color = withAlpha(
            style.tint.accentColor,
            (225f * opacity.coerceAtLeast(0.55f)).toInt(),
        )
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        detailPaint.strokeWidth = diameter * 0.045f
        detailPaint.color = withAlpha(Color.WHITE, (150f * opacity).toInt())
        canvas.drawArc(
            centerX - radius * 0.78f,
            centerY - radius * 0.78f,
            centerX + radius * 0.78f,
            centerY + radius * 0.78f,
            205f,
            105f,
            false,
            detailPaint,
        )

        when (feedback) {
            Feedback.NORMAL -> drawRewindIcon(canvas, diameter)
            Feedback.ACCEPTED -> drawAcceptedIcon(canvas, diameter)
            Feedback.SAVED -> drawCheckIcon(canvas, diameter)
            Feedback.BUSY -> drawBusyIcon(canvas, diameter)
            Feedback.FAILED -> drawFailureIcon(canvas, diameter)
        }
    }

    fun animatePress(pressed: Boolean) {
        animate()
            .scaleX(if (pressed) 0.88f else 1f)
            .scaleY(if (pressed) 0.88f else 1f)
            .setDuration(if (pressed) 75L else 130L)
            .start()
    }

    fun showAcceptedFeedback() {
        setFeedback(Feedback.ACCEPTED, 650L)
        animate()
            .scaleX(1.10f)
            .scaleY(1.10f)
            .setDuration(110L)
            .withEndAction {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
    }

    fun showSavedFeedback() {
        setFeedback(Feedback.SAVED, 900L)
    }

    fun showBusyFeedback() {
        setFeedback(Feedback.BUSY, 700L)
    }

    fun showFailureFeedback() {
        setFeedback(Feedback.FAILED, 1100L)
    }

    private fun setFeedback(value: Feedback, durationMs: Long) {
        clearFeedback?.let(handler::removeCallbacks)
        feedback = value
        invalidate()
        val clear = Runnable {
            feedback = Feedback.NORMAL
            invalidate()
        }
        clearFeedback = clear
        handler.postDelayed(clear, durationMs)
    }

    private fun drawRewindIcon(canvas: Canvas, diameter: Float) {
        iconPaint.color = style.tint.accentColor
        val cy = height / 2f
        val triangleHeight = diameter * 0.28f
        val triangleWidth = diameter * 0.20f
        val left = width / 2f - diameter * 0.17f

        drawLeftTriangle(canvas, left, cy, triangleWidth, triangleHeight)
        drawLeftTriangle(
            canvas,
            left + diameter * 0.20f,
            cy,
            triangleWidth,
            triangleHeight,
        )
    }

    private fun drawLeftTriangle(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        triangleWidth: Float,
        triangleHeight: Float,
    ) {
        val path = Path().apply {
            moveTo(centerX - triangleWidth / 2f, centerY)
            lineTo(
                centerX + triangleWidth / 2f,
                centerY - triangleHeight / 2f,
            )
            lineTo(
                centerX + triangleWidth / 2f,
                centerY + triangleHeight / 2f,
            )
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawAcceptedIcon(canvas: Canvas, diameter: Float) {
        detailPaint.color = style.tint.accentColor
        iconPaint.color = style.tint.accentColor
        detailPaint.strokeWidth = diameter * 0.065f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, diameter * 0.15f, detailPaint)
        canvas.drawCircle(cx, cy, diameter * 0.04f, iconPaint)
    }

    private fun drawCheckIcon(canvas: Canvas, diameter: Float) {
        detailPaint.color = style.tint.accentColor
        detailPaint.strokeWidth = diameter * 0.075f
        val path = Path().apply {
            moveTo(width * 0.30f, height * 0.52f)
            lineTo(width * 0.44f, height * 0.66f)
            lineTo(width * 0.72f, height * 0.35f)
        }
        canvas.drawPath(path, detailPaint)
    }

    private fun drawBusyIcon(canvas: Canvas, diameter: Float) {
        iconPaint.color = style.tint.accentColor
        val cy = height / 2f
        val radius = diameter * 0.035f
        for (offset in listOf(-0.13f, 0f, 0.13f)) {
            canvas.drawCircle(
                width / 2f + diameter * offset,
                cy,
                radius,
                iconPaint,
            )
        }
    }

    private fun drawFailureIcon(canvas: Canvas, diameter: Float) {
        detailPaint.color = style.tint.accentColor
        iconPaint.color = style.tint.accentColor
        detailPaint.strokeWidth = diameter * 0.07f
        canvas.drawLine(
            width / 2f,
            height * 0.31f,
            width / 2f,
            height * 0.59f,
            detailPaint,
        )
        canvas.drawCircle(
            width / 2f,
            height * 0.70f,
            diameter * 0.035f,
            iconPaint,
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }
}
