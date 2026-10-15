package com.medplay.app.recording

import android.content.Context

object FloatingControlStylePreference {

    enum class Tint(
        val key: String,
        val startColor: Int,
        val endColor: Int,
        val accentColor: Int,
    ) {
        ICE(
            "ice",
            0xFFD8F3FF.toInt(),
            0xFF9BC7D9.toInt(),
            0xFFFFFFFF.toInt(),
        ),
        SKY(
            "sky",
            0xFF75C9FF.toInt(),
            0xFF326FA8.toInt(),
            0xFFEAF8FF.toInt(),
        ),
        VIOLET(
            "violet",
            0xFFC5A8FF.toInt(),
            0xFF6840B4.toInt(),
            0xFFF7F0FF.toInt(),
        ),
        EMERALD(
            "emerald",
            0xFF78E6C2.toInt(),
            0xFF287C68.toInt(),
            0xFFEFFFFA.toInt(),
        ),
        AMBER(
            "amber",
            0xFFFFD37A.toInt(),
            0xFF9B641F.toInt(),
            0xFFFFF8E8.toInt(),
        ),
    }

    data class Style(
        val sizeDp: Int,
        val opacityPercent: Int,
        val tint: Tint,
    )

    fun get(context: Context): Style {
        val preferences = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val tintKey = preferences.getString(KEY_TINT, Tint.ICE.key)
        return Style(
            sizeDp = clampSize(
                preferences.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
            ),
            opacityPercent = clampOpacity(
                preferences.getInt(KEY_OPACITY_PERCENT, DEFAULT_OPACITY_PERCENT)
            ),
            tint = Tint.entries.firstOrNull { it.key == tintKey } ?: Tint.ICE,
        )
    }

    fun setSizeDp(context: Context, value: Int) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SIZE_DP, clampSize(value))
            .apply()
    }

    fun setOpacityPercent(context: Context, value: Int) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY_PERCENT, clampOpacity(value))
            .apply()
    }

    fun setTint(context: Context, tint: Tint) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TINT, tint.key)
            .apply()
    }

    fun clampSize(value: Int): Int = value.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun clampOpacity(value: Int): Int =
        value.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)

    const val MIN_SIZE_DP = 44
    const val MAX_SIZE_DP = 88
    const val DEFAULT_SIZE_DP = 60

    const val MIN_OPACITY_PERCENT = 30
    const val MAX_OPACITY_PERCENT = 95
    const val DEFAULT_OPACITY_PERCENT = 68

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_SIZE_DP = "floating_control_size_dp"
    private const val KEY_OPACITY_PERCENT = "floating_control_opacity_percent"
    private const val KEY_TINT = "floating_control_tint"
}
