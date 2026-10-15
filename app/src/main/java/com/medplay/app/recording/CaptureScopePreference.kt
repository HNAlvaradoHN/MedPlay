package com.medplay.app.recording

import android.content.Context

object CaptureScopePreference {

    enum class Mode(val key: String) {
        APP_ONLY("app_only"),
        FULL_SCREEN("full_screen"),
    }

    fun get(context: Context): Mode {
        val key = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.APP_ONLY.key)

        return Mode.entries.firstOrNull { it.key == key }
            ?: Mode.APP_ONLY
    }

    fun set(context: Context, mode: Mode) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.key)
            .apply()
    }

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_MODE = "capture_scope_mode"
}
