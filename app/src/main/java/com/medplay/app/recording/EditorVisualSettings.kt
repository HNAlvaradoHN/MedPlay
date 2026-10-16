package com.medplay.app.recording

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

object EditorVisualSettings {

    enum class FilterPreset {
        NATURAL,
        VIVID,
        WARM,
        COOL,
        CINEMATIC,
        MONO,
    }

    data class Values(
        val filterPreset: FilterPreset = FilterPreset.NATURAL,
        val saturationPercent: Int = 100,
        val brightnessPercent: Int = 0,
        val contrastPercent: Int = 0,
    )

    fun sanitize(values: Values): Values {
        return values.copy(
            saturationPercent = values.saturationPercent.coerceIn(0, 200),
            brightnessPercent = values.brightnessPercent.coerceIn(-50, 50),
            contrastPercent = values.contrastPercent.coerceIn(-50, 50),
        )
    }

    @OptIn(UnstableApi::class)
    fun buildVideoEffects(values: Values): List<Effect> {
        val safe = sanitize(values)
        val effects = mutableListOf<Effect>()

        when (safe.filterPreset) {
            FilterPreset.NATURAL -> Unit

            FilterPreset.VIVID -> {
                effects += HslAdjustment.Builder()
                    .adjustSaturation(20f)
                    .build()
                effects += Contrast(0.12f)
            }

            FilterPreset.WARM -> {
                effects += RgbAdjustment.Builder()
                    .setRedScale(1.08f)
                    .setGreenScale(1.01f)
                    .setBlueScale(0.92f)
                    .build()
            }

            FilterPreset.COOL -> {
                effects += RgbAdjustment.Builder()
                    .setRedScale(0.94f)
                    .setGreenScale(1.00f)
                    .setBlueScale(1.08f)
                    .build()
            }

            FilterPreset.CINEMATIC -> {
                effects += HslAdjustment.Builder()
                    .adjustSaturation(-8f)
                    .adjustLightness(-3f)
                    .build()
                effects += Contrast(0.18f)
            }

            FilterPreset.MONO -> {
                effects += HslAdjustment.Builder()
                    .adjustSaturation(-100f)
                    .build()
            }
        }

        val saturationAdjustment = safe.saturationPercent - 100
        if (saturationAdjustment != 0) {
            effects += HslAdjustment.Builder()
                .adjustSaturation(saturationAdjustment.toFloat())
                .build()
        }

        if (safe.brightnessPercent != 0) {
            effects += Brightness(safe.brightnessPercent / 100f)
        }

        if (safe.contrastPercent != 0) {
            effects += Contrast(safe.contrastPercent / 100f)
        }

        return effects
    }

    fun hasVisualChanges(values: Values): Boolean {
        val safe = sanitize(values)
        return safe.filterPreset != FilterPreset.NATURAL ||
            safe.saturationPercent != 100 ||
            safe.brightnessPercent != 0 ||
            safe.contrastPercent != 0
    }
}
