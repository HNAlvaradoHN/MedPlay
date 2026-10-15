package com.medplay.app.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object EncoderCapabilityProbe {

    data class EncoderSummary(
        val name: String,
        val codec: VideoCodec,
        val hardwareAccelerated: Boolean,
        val bitrateRange: String,
        val widthRange: String,
        val heightRange: String,
        val frameRateRange: String,
    )

    fun inspect(): List<EncoderSummary> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filterNot { info -> Build.VERSION.SDK_INT >= 29 && info.isAlias }
            .flatMap { info ->
                VideoCodec.entries.asSequence().mapNotNull { codec ->
                    if (!info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }) {
                        return@mapNotNull null
                    }

                    val caps = runCatching { info.getCapabilitiesForType(codec.mimeType) }.getOrNull()
                        ?: return@mapNotNull null
                    val videoCaps = caps.videoCapabilities ?: return@mapNotNull null

                    EncoderSummary(
                        name = info.name,
                        codec = codec,
                        hardwareAccelerated = isHardwareAccelerated(info),
                        bitrateRange = videoCaps.bitrateRange.toString(),
                        widthRange = videoCaps.supportedWidths.toString(),
                        heightRange = videoCaps.supportedHeights.toString(),
                        frameRateRange = videoCaps.supportedFrameRates.toString(),
                    )
                }
            }
            .sortedWith(
                compareByDescending<EncoderSummary> { it.hardwareAccelerated }
                    .thenBy { it.codec.ordinal }
                    .thenBy { it.name }
            )
            .toList()
    }

    fun buildTextReport(): String {
        val encoders = inspect()
        if (encoders.isEmpty()) {
            return "No compatible video encoders were reported by Android."
        }

        return buildString {
            appendLine("MedPlay encoder capability report")
            appendLine("Hardware encoders are preferred for sustained recording.")
            appendLine()
            encoders.forEach { encoder ->
                appendLine("${encoder.codec.name} | ${encoder.name}")
                appendLine("  hardware=${encoder.hardwareAccelerated}")
                appendLine("  bitrate=${encoder.bitrateRange}")
                appendLine("  width=${encoder.widthRange}")
                appendLine("  height=${encoder.heightRange}")
                appendLine("  fps=${encoder.frameRateRange}")
                appendLine()
            }
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            val lower = info.name.lowercase()
            !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
        }
    }
}
