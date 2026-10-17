package com.medplay.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class ClipTrimTimelineView(context: Context) : View(context) {

    fun interface Listener {
        fun onRangeChanged(startUs: Long, endUs: Long, fromUser: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 70, 76)
        strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MediaUiStyle.ACCENT
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MediaUiStyle.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 0, 0, 0)
    }

    private var durationUs = 1L
    private var startUs = 0L
    private var endUs = 1L
    private var draggingStart = false
    private var draggingEnd = false
    private var listener: Listener? = null

    fun setListener(value: Listener?) {
        listener = value
    }

    fun setRange(
        startUs: Long,
        endUs: Long,
        durationUs: Long,
        notify: Boolean = false,
    ) {
        this.durationUs = durationUs.coerceAtLeast(1L)
        this.startUs = startUs.coerceIn(0L, this.durationUs)
        this.endUs = endUs.coerceIn(this.startUs, this.durationUs)
        invalidate()
        if (notify) {
            listener?.onRangeChanged(this.startUs, this.endUs, false)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (84f * density).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 20f * density
        val right = width - paddingRight - 20f * density
        val y = height / 2f
        val usable = (right - left).coerceAtLeast(1f)

        val startX = left + usable * startUs.toFloat() / durationUs.toFloat()
        val endX = left + usable * endUs.toFloat() / durationUs.toFloat()

        canvas.drawLine(left, y, right, y, trackPaint)
        canvas.drawRect(left, 0f, startX, height.toFloat(), shadePaint)
        canvas.drawRect(endX, 0f, right, height.toFloat(), shadePaint)
        canvas.drawLine(startX, y, endX, y, selectedPaint)

        val radius = 14f * density
        canvas.drawCircle(startX, y, radius, handlePaint)
        canvas.drawCircle(endX, y, radius, handlePaint)
        canvas.drawCircle(startX, y, radius, handleStrokePaint)
        canvas.drawCircle(endX, y, radius, handleStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationUs <= 0L) return false
        val left = paddingLeft + 20f * density
        val right = width - paddingRight - 20f * density
        val usable = (right - left).coerceAtLeast(1f)
        val startX = left + usable * startUs.toFloat() / durationUs.toFloat()
        val endX = left + usable * endUs.toFloat() / durationUs.toFloat()
        val hit = 36f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ds = abs(event.x - startX)
                val de = abs(event.x - endX)
                draggingStart = ds <= de && ds <= hit
                draggingEnd = !draggingStart && de <= hit
                if (!draggingStart && !draggingEnd) {
                    draggingStart = ds <= de
                    draggingEnd = !draggingStart
                }
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val fraction = ((event.x - left) / usable).coerceIn(0f, 1f)
                val valueUs = (fraction * durationUs).toLong()
                if (draggingStart) {
                    startUs = valueUs.coerceIn(0L, endUs)
                } else if (draggingEnd) {
                    endUs = valueUs.coerceIn(startUs, durationUs)
                }
                invalidate()
                listener?.onRangeChanged(startUs, endUs, true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingStart = false
                draggingEnd = false
                isPressed = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
