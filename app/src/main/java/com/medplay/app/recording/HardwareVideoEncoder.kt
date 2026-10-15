package com.medplay.app.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HardwareVideoEncoder(
    private val profile: RecordingProfile,
    private val sampleConsumer: SampleConsumer = SampleConsumer.Discard,
    private val onFatalError: (Throwable) -> Unit = {},
) {

    interface SampleConsumer {
        fun onEncoderStarted(monotonicStartUs: Long) = Unit
        fun onOutputFormat(format: MediaFormat)
        fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)

        object Discard : SampleConsumer {
            override fun onOutputFormat(format: MediaFormat) = Unit
            override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
        }
    }

    private var codec: MediaCodec? = null
    private val runtimeHealth = EncoderRuntimeHealth()
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    fun start(): Surface {
        check(codec == null) { "Encoder already started" }

        val createdCodec = MediaCodec.createByCodecName(profile.encoderName)
        var surface: Surface? = null
        var codecStarted = false

        try {
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

            surface = createdCodec.createInputSurface()
            val monotonicStartUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            createdCodec.start()
            codecStarted = true
            sampleConsumer.onEncoderStarted(monotonicStartUs)
        } catch (error: Throwable) {
            runCatching { surface?.release() }
            if (codecStarted) {
                runCatching { createdCodec.stop() }
            }
            runCatching { createdCodec.release() }
            throw error
        }

        val readySurface = checkNotNull(surface)
        codec = createdCodec
        inputSurface = readySurface
        stopRequested.set(false)
        running.set(true)

        drainThread = thread(
            start = true,
            isDaemon = true,
            name = "medplay-video-drain",
        ) {
            drainLoop(createdCodec)
        }

        return readySurface
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

    @Synchronized
    fun requestSyncFrame(): Boolean {
        val currentCodec = codec ?: return false
        if (!running.get() || stopRequested.get()) return false

        return runCatching {
            currentCodec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            true
        }.getOrDefault(false)
    }

    fun healthSnapshot(): EncoderRuntimeHealth.Snapshot {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        return runtimeHealth.snapshot(nowUs)
    }

    @Synchronized
    fun stop() {
        val currentCodec = codec ?: return
        if (stopRequested.compareAndSet(false, true)) {
            runCatching { currentCodec.signalEndOfInputStream() }
        }

        drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
        if (drainThread?.isAlive == true) {
            running.set(false)
            drainThread?.join(FORCE_DRAIN_JOIN_TIMEOUT_MS)
        }
        drainThread = null

        inputSurface?.release()
        inputSurface = null

        runCatching { currentCodec.stop() }
        runCatching { currentCodec.release() }
        codec = null
        running.set(false)
    }

    private fun drainLoop(currentCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var eosSeen = false

        try {
            while (running.get() && !eosSeen) {
                when (val index = currentCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested.get()) {
                            continue
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleConsumer.onOutputFormat(currentCodec.outputFormat)
                    }
                    else -> {
                        if (index >= 0) {
                            try {
                                val output = currentCodec.getOutputBuffer(index)
                                if (output != null && info.size > 0) {
                                    runtimeHealth.onEncodedSample(
                                        presentationTimeUs = info.presentationTimeUs,
                                        wallTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
                                    )
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    sampleConsumer.onSample(output.slice(), info)
                                }

                                if (
                                    (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                ) {
                                    eosSeen = true
                                }
                            } finally {
                                currentCodec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopRequested.get()) {
                onFatalError(error)
            }
        } finally {
            running.set(false)
        }
    }

    companion object {
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 1_500L
        private const val FORCE_DRAIN_JOIN_TIMEOUT_MS = 250L
        private const val MIN_DYNAMIC_BITRATE = 2_000_000
    }
}
