package com.medplay.app.recording

import android.content.Context

object RecordingModePreference {

    enum class Mode {
        RETROACTIVE_CLIP,
        FULL_SESSION,
    }

    fun get(context: Context): Mode {
        val stored = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.RETROACTIVE_CLIP.name)

        return runCatching { Mode.valueOf(stored.orEmpty()) }
            .getOrDefault(Mode.RETROACTIVE_CLIP)
    }

    fun set(context: Context, mode: Mode) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_MODE = "recording_mode"
}
