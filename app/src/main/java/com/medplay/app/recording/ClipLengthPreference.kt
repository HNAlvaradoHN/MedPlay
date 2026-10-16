package com.medplay.app.recording

import android.content.Context

object ClipLengthPreference {

    val allowedSeconds = listOf(15, 30, 60)

    fun get(context: Context): Int {
        val stored = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_SECONDS, DEFAULT_SECONDS)
        return sanitize(stored)
    }

    fun set(context: Context, seconds: Int) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_SECONDS, sanitize(seconds))
            .apply()
    }

    fun sanitize(seconds: Int): Int {
        return if (seconds in allowedSeconds) seconds else DEFAULT_SECONDS
    }

    const val DEFAULT_SECONDS = 30

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_DEFAULT_SECONDS = "default_clip_seconds"
}
