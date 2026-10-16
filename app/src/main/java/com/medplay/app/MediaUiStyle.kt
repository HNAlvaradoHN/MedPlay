package com.medplay.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

object MediaUiStyle {

    const val BACKGROUND = 0xFF0F1012.toInt()
    const val SURFACE = 0xFF1A1C20.toInt()
    const val SURFACE_ELEVATED = 0xFF23262B.toInt()
    const val PRIMARY_TEXT = Color.WHITE
    const val SECONDARY_TEXT = 0xFFB7BBC3.toInt()
    const val ACCENT = 0xFF6C63FF.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    fun rounded(
        color: Int,
        radiusDp: Int,
        context: Context,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(
                    dp(context, strokeWidthDp),
                    strokeColor,
                )
            }
        }
    }

    fun applyPressableSurface(view: View, elevated: Boolean = false) {
        view.background = rounded(
            color = if (elevated) SURFACE_ELEVATED else SURFACE,
            radiusDp = 16,
            context = view.context,
        )
    }
}
