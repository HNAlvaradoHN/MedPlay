package com.medplay.app.recording

import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.Aec3Metrics
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Shared software acoustic echo cancellation pipeline.
 *
 * Internal playback PCM is downmixed to mono and queued as the far-end
 * reference. Microphone PCM is processed as the near-end stream in 10 ms
 * frames before AAC encoding.
 *
 * AEC3 objects are owned by this class and touched under one monitor because
 * the playback and microphone AudioRecord loops run on different threads.
 */
class WebRtcEchoCancellationPipeline(
    private val sampleRate: Int = SAMPLE_RATE,
    initialDelayMs: Int = DEFAULT_DELAY_MS,
    private val onMetrics: (Metrics) -> Unit = {},
) : AutoCloseable {

    data class Metrics(
        val processedFrames: Long,
        val renderFrames: Long,
        val droppedRenderFrames: Long,
        val echoReturnLoss: Double,
        val echoReturnLossEnhancement: Double,
        val estimatedDelayMs: Int,
    )

    private data class RenderFrame(
        val startUs: Long,
        val samples: ShortArray,
    )

    private val config: Aec3Config = createAec3Config().apply {
        setDelayDefaultDelay(initialDelayMs)
        setFilterInitialStateSeconds(0.5f)
        setFilterConservativeInitialPhase(false)
        check(validate()) { "Invalid WebRTC AEC3 configuration" }
    }
    private val environment: Aec3Environment = createAec3Environment()
    private val factory: Aec3Factory = createAec3FactoryWithConfig(config)
    private val echoControl: Aec3EchoControl = createAec3EchoControl(
        factory,
        environment,
        sampleRate,
        1,
        1,
    ).also {
        it.setAudioBufferDelay(initialDelayMs)
    }
    private val renderBuffer: Aec3AudioBuffer =
        createAec3AudioBuffer(sampleRate, 1)
    private val captureBuffer: Aec3AudioBuffer =
        createAec3AudioBuffer(sampleRate, 1)

    private val renderQueue = ArrayDeque<RenderFrame>()
    private val renderCarry = ShortArray(FRAME_SAMPLES)
    private var renderCarryCount = 0
    private var renderCarryStartUs = Long.MIN_VALUE

    private val renderFloats = FloatArray(FRAME_SAMPLES)
    private val captureFloats = FloatArray(FRAME_SAMPLES)

    private var processedFrames = 0L
    private var renderFrames = 0L
    private var droppedRenderFrames = 0L
    private var captureSilenced = false
    private var levelChangePending = false
    private var closed = false
    private var hasAnalyzedRender = false

    @Synchronized
    fun submitRenderPcm(
        buffer: ByteBuffer,
        bytesRead: Int,
        firstFrameStartUs: Long,
        channelCount: Int,
    ) {
        if (closed || bytesRead <= 0 || channelCount <= 0) return

        val probe = buffer.duplicate().order(ByteOrder.nativeOrder())
        probe.position(0)
        probe.limit(bytesRead.coerceAtMost(probe.capacity()))

        val totalFrames = probe.remaining() / (channelCount * BYTES_PER_SAMPLE)
        var frameIndex = 0
        while (frameIndex < totalFrames) {
            var mixed = 0
            for (channel in 0 until channelCount) {
                mixed += probe.short.toInt()
            }
            val mono = (mixed / channelCount)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            if (renderCarryCount == 0) {
                renderCarryStartUs =
                    firstFrameStartUs + framesToUs(frameIndex.toLong())
            }
            renderCarry[renderCarryCount++] = mono

            if (renderCarryCount == FRAME_SAMPLES) {
                enqueueRenderFrame(
                    RenderFrame(
                        startUs = renderCarryStartUs,
                        samples = renderCarry.copyOf(),
                    )
                )
                renderCarryCount = 0
                renderCarryStartUs = Long.MIN_VALUE
            }
            frameIndex++
        }
    }

    /**
     * Processes exactly one 10 ms microphone frame.
     *
     * When Android temporarily silences this recorder because the game owns the
     * microphone, AEC adaptation is suspended and the render backlog is dropped.
     * The next real capture frame is marked as a level change so AEC3 can
     * re-adapt without training against digital silence.
     */
    @Synchronized
    fun processCaptureFrame(
        input: ShortArray,
        output: ShortArray,
        captureFrameStartUs: Long,
        silenced: Boolean,
    ) {
        require(input.size >= FRAME_SAMPLES)
        require(output.size >= FRAME_SAMPLES)
        if (closed) {
            input.copyInto(output, 0, 0, FRAME_SAMPLES)
            return
        }

        if (silenced) {
            if (!captureSilenced) {
                renderQueue.clear()
                levelChangePending = true
            }
            captureSilenced = true
            input.copyInto(output, 0, 0, FRAME_SAMPLES)
            return
        }

        if (captureSilenced) {
            captureSilenced = false
            levelChangePending = true
        }

        drainRenderUntil(captureFrameStartUs)

        if (!hasAnalyzedRender) {
            input.copyInto(output, 0, 0, FRAME_SAMPLES)
            return
        }

        for (i in 0 until FRAME_SAMPLES) {
            captureFloats[i] = input[i].toFloat()
        }
        captureBuffer.writeChannel(0, captureFloats)
        echoControl.analyzeCapture(captureBuffer)
        echoControl.processCapture(captureBuffer, levelChangePending)
        levelChangePending = false

        val processed = captureBuffer.readChannel(0)
        for (i in 0 until FRAME_SAMPLES) {
            output[i] = processed[i]
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        processedFrames++
        if (processedFrames % METRICS_INTERVAL_FRAMES == 0L) {
            publishMetrics(echoControl.getMetrics())
        }
    }

    @Synchronized
    fun setAudioBufferDelay(delayMs: Int) {
        if (closed) return
        echoControl.setAudioBufferDelay(delayMs.coerceIn(0, MAX_DELAY_MS))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderQueue.clear()

        runCatching { renderBuffer.close() }
        runCatching { captureBuffer.close() }
        runCatching { echoControl.close() }
        runCatching { factory.close() }
        runCatching { environment.close() }
        runCatching { config.close() }
    }

    private fun enqueueRenderFrame(frame: RenderFrame) {
        while (renderQueue.size >= MAX_RENDER_QUEUE_FRAMES) {
            renderQueue.removeFirst()
            droppedRenderFrames++
        }
        renderQueue.addLast(frame)
    }

    private fun drainRenderUntil(captureFrameStartUs: Long) {
        while (renderQueue.isNotEmpty()) {
            val frame = renderQueue.first()
            if (frame.startUs > captureFrameStartUs + RENDER_LEAD_TOLERANCE_US) {
                break
            }
            renderQueue.removeFirst()
            for (i in 0 until FRAME_SAMPLES) {
                renderFloats[i] = frame.samples[i].toFloat()
            }
            renderBuffer.writeChannel(0, renderFloats)
            echoControl.analyzeRender(renderBuffer)
            hasAnalyzedRender = true
            renderFrames++
        }
    }

    private fun publishMetrics(metrics: Aec3Metrics) {
        onMetrics(
            Metrics(
                processedFrames = processedFrames,
                renderFrames = renderFrames,
                droppedRenderFrames = droppedRenderFrames,
                echoReturnLoss = metrics.echoReturnLoss,
                echoReturnLossEnhancement =
                    metrics.echoReturnLossEnhancement,
                estimatedDelayMs = metrics.delayMs,
            )
        )
    }

    private fun framesToUs(frames: Long): Long {
        return frames * 1_000_000L / sampleRate
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100
        const val FRAME_DURATION_US = 10_000L

        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_DELAY_MS = 25
        private const val MAX_DELAY_MS = 500
        private const val MAX_RENDER_QUEUE_FRAMES = 80
        private const val RENDER_LEAD_TOLERANCE_US = 2_000L
        private const val METRICS_INTERVAL_FRAMES = 50L
    }
}
