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
        val surfaceInputSupported: Boolean,
    )

    fun inspect(): List<EncoderSummary> {
        return encoderInfos()
            .flatMap { info ->
                VideoCodec.entries.mapNotNull { codec ->
                    val caps = capabilities(info, codec) ?: return@mapNotNull null
                    val videoCaps = caps.videoCapabilities ?: return@mapNotNull null
                    EncoderSummary(
                        name = info.name,
                        codec = codec,
                        hardwareAccelerated = isHardwareAccelerated(info),
                        bitrateRange = videoCaps.bitrateRange.toString(),
                        widthRange = videoCaps.supportedWidths.toString(),
                        heightRange = videoCaps.supportedHeights.toString(),
                        frameRateRange = videoCaps.supportedFrameRates.toString(),
                        surfaceInputSupported = caps.colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        ),
                    )
                }
            }
            .sortedWith(
                compareByDescending<EncoderSummary> { it.hardwareAccelerated }
                    .thenBy { it.codec.ordinal }
                    .thenBy { it.name }
            )
    }

    fun supportedStartupProfiles(): List<SustainableProfilePolicy.SupportedProfile> {
        val targets = SustainableProfilePolicy.startupTargets

        return encoderInfos().flatMap { info ->
            VideoCodec.entries.flatMap { codec ->
                val caps = capabilities(info, codec) ?: return@flatMap emptyList()
                val videoCaps = caps.videoCapabilities ?: return@flatMap emptyList()
                val hardware = isHardwareAccelerated(info)
                val surfaceInputSupported = caps.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                if (!surfaceInputSupported) return@flatMap emptyList()

                targets
                    .asSequence()
                    .filter { it.codec == codec }
                    .filter { target ->
                        runCatching {
                            videoCaps.areSizeAndRateSupported(
                                target.width,
                                target.height,
                                target.fps.toDouble(),
                            )
                        }.getOrDefault(false)
                    }
                    .mapNotNull { target ->
                        val range = videoCaps.bitrateRange
                        if (!range.contains(target.bitrate)) return@mapNotNull null

                        SustainableProfilePolicy.SupportedProfile(
                            encoderName = info.name,
                            codec = codec,
                            hardwareAccelerated = hardware,
                            width = target.width,
                            height = target.height,
                            fps = target.fps,
                            bitrate = target.bitrate,
                        )
                    }
                    .toList()
            }
        }
    }

    fun chooseSafeStartupProfile(
        mode: RecordingQualityPreference.Mode = RecordingQualityPreference.Mode.AUTO,
    ): RecordingProfile? {
        return RecordingQualityPreference.choose(
            mode = mode,
            supported = supportedStartupProfiles(),
        )
    }

    fun supportedQualityModes(): List<RecordingQualityPreference.Mode> {
        return RecordingQualityPreference.supportedModes(
            supportedStartupProfiles()
        )
    }

    fun buildTextReport(): String {
        val encoders = inspect()
        val selected = chooseSafeStartupProfile()

        return buildString {
            appendLine("MedPlay encoder capability report")
            appendLine("Hardware encoders are required for the automatic startup profile.")
            appendLine()
            if (selected == null) {
                appendLine("AUTO PROFILE: none - no validated hardware startup target.")
            } else {
                appendLine(
                    "AUTO PROFILE: ${selected.width}x${selected.height} " +
                        "${selected.fps}fps ${selected.codec.name} " +
                        "${selected.bitrate / 1_000_000}Mbps"
                )
                appendLine("ENCODER: ${selected.encoderName}")
            }
            appendLine()

            if (encoders.isEmpty()) {
                appendLine("No compatible video encoders were reported by Android.")
            } else {
                encoders.forEach { encoder ->
                    appendLine("${encoder.codec.name} | ${encoder.name}")
                    appendLine("  hardware=${encoder.hardwareAccelerated}")
                    appendLine("  bitrate=${encoder.bitrateRange}")
                    appendLine("  width=${encoder.widthRange}")
                    appendLine("  height=${encoder.heightRange}")
                    appendLine("  fps=${encoder.frameRateRange}")
                    appendLine("  surfaceInput=${encoder.surfaceInputSupported}")
                    appendLine()
                }
            }
        }
    }

    private fun encoderInfos(): List<MediaCodecInfo> {
        return MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filterNot { info -> Build.VERSION.SDK_INT >= 29 && info.isAlias }
            .toList()
    }

    private fun capabilities(
        info: MediaCodecInfo,
        codec: VideoCodec,
    ): MediaCodecInfo.CodecCapabilities? {
        if (!info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }) {
            return null
        }
        return runCatching { info.getCapabilitiesForType(codec.mimeType) }.getOrNull()
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
