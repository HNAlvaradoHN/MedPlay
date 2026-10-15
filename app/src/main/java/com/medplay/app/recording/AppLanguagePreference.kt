package com.medplay.app.recording

import android.content.Context

object AppLanguagePreference {

    enum class Language(val key: String) {
        SPANISH("es"),
        ENGLISH("en"),
    }

    fun get(context: Context): Language {
        val key = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, Language.SPANISH.key)

        return Language.entries.firstOrNull { it.key == key }
            ?: Language.SPANISH
    }

    fun set(context: Context, language: Language) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.key)
            .apply()
    }

    fun text(
        context: Context,
        spanish: String,
        english: String,
    ): String {
        return if (get(context) == Language.SPANISH) spanish else english
    }

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_LANGUAGE = "app_language"
}
