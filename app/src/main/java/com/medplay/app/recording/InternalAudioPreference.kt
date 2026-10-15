package com.medplay.app.recording

import android.content.Context

object InternalAudioPreference {

    fun isEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    const val DEFAULT_ENABLED = true

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_ENABLED = "internal_audio_enabled"
}
