package com.medplay.app.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HardwareVideoEncoder(
    private val profile: RecordingProfile,
    private val sampleConsumer: SampleConsumer = SampleConsumer.Discard,
) {

    interface SampleConsumer {
        fun onOutputFormat(format: MediaFormat)
        fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)

        object Discard : SampleConsumer {
            override fun onOutputFormat(format: MediaFormat) = Unit
            override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
        }
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start(): Surface {
        check(codec == null) { "Encoder already started" }

        val createdCodec = MediaCodec.createByCodecName(profile.encoderName)
        val format = MediaFormat.createVideoFormat(
            profile.codec.mimeType,
            profile.width,
            profile.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        createdCodec.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )

        val surface = createdCodec.createInputSurface()
        createdCodec.start()

        codec = createdCodec
        inputSurface = surface
        running.set(true)

        drainThread = thread(
            start = true,
            isDaemon = true,
            name = "medplay-video-drain",
        ) {
            drainLoop(createdCodec)
        }

        return surface
    }

    @Synchronized
    fun updateBitrate(targetBitrate: Int): Boolean {
        val currentCodec = codec ?: return false
        if (!running.get()) return false

        val safeBitrate = targetBitrate.coerceIn(MIN_DYNAMIC_BITRATE, profile.bitrate)
        return runCatching {
            currentCodec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, safeBitrate)
                }
            )
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!running.getAndSet(false) && codec == null) return

        val currentCodec = codec
        runCatching { currentCodec?.signalEndOfInputStream() }

        drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
        drainThread = null

        inputSurface?.release()
        inputSurface = null

        runCatching { currentCodec?.stop() }
        runCatching { currentCodec?.release() }
        codec = null
    }

    private fun drainLoop(currentCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        while (running.get()) {
            when (val index = currentCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sampleConsumer.onOutputFormat(currentCodec.outputFormat)
                }
                else -> {
                    if (index >= 0) {
                        val output = currentCodec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            sampleConsumer.onSample(output.slice(), info)
                        }

                        currentCodec.releaseOutputBuffer(index, false)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            running.set(false)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 1_000L
        private const val MIN_DYNAMIC_BITRATE = 2_000_000
    }
}
