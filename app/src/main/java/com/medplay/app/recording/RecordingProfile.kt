package com.medplay.app.recording

data class RecordingProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: VideoCodec,
)

enum class VideoCodec(val mimeType: String) {
    AVC("video/avc"),
    HEVC("video/hevc"),
    AV1("video/av01"),
}
