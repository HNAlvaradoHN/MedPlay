package com.medplay.app.recording

import android.content.Context

object RecordingQualityPreference {

    enum class Mode(
        val key: String,
        val width: Int?,
        val height: Int?,
        val fps: Int?,
    ) {
        AUTO("auto", null, null, null),
        P720_30("720p30", 1280, 720, 30),
        P720_60("720p60", 1280, 720, 60),
        P1080_30("1080p30", 1920, 1080, 30),
        P1080_60("1080p60", 1920, 1080, 60),
        P1440_30("1440p30", 2560, 1440, 30),
        P1440_60("1440p60", 2560, 1440, 60),
    }

    fun get(context: Context): Mode {
        val key = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.AUTO.key)

        return Mode.entries.firstOrNull { it.key == key } ?: Mode.AUTO
    }

    fun set(context: Context, mode: Mode) {
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.key)
            .apply()
    }

    fun supportedModes(
        supported: List<SustainableProfilePolicy.SupportedProfile>,
    ): List<Mode> {
        return buildList {
            add(Mode.AUTO)
            Mode.entries
                .filter { it != Mode.AUTO }
                .filter { mode ->
                    supported.any {
                        it.hardwareAccelerated &&
                            it.width == mode.width &&
                            it.height == mode.height &&
                            it.fps == mode.fps
                    }
                }
                .forEach(::add)
        }
    }

    fun choose(
        mode: Mode,
        supported: List<SustainableProfilePolicy.SupportedProfile>,
    ): RecordingProfile? {
        if (mode == Mode.AUTO) {
            return SustainableProfilePolicy.chooseStartupProfile(supported)
        }

        val matching = supported
            .filter { it.hardwareAccelerated }
            .filter {
                it.width == mode.width &&
                    it.height == mode.height &&
                    it.fps == mode.fps
            }

        val preferred = matching.firstOrNull { it.codec == VideoCodec.HEVC }
            ?: matching.firstOrNull { it.codec == VideoCodec.AVC }
            ?: matching.firstOrNull()

        return preferred?.toRecordingProfile()
            ?: SustainableProfilePolicy.chooseStartupProfile(supported)
    }

    private const val PREFERENCES_NAME = "medplay_recording"
    private const val KEY_MODE = "recording_quality_mode"
}
