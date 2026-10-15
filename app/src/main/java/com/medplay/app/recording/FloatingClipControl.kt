package com.medplay.app.recording

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class FloatingClipControl(
    private val context: Context,
    private val onSaveClip: () -> Unit,
) : AutoCloseable {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val density = context.resources.displayMetrics.density
        val sizePx = (56f * density).toInt()

        val bubble = TextView(context).apply {
            text = "Clip"
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(0, 0, 0, 0)
            setBackgroundResource(android.R.drawable.btn_default)
        }

        val layout = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12f * density).toInt()
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
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - downX).toInt()
                    layout.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(bubble, layout) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved < (12f * density)) {
                        onSaveClip()
                    }
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

    override fun close() {
        val current = view ?: return
        runCatching { windowManager.removeView(current) }
        view = null
        params = null
    }
}
