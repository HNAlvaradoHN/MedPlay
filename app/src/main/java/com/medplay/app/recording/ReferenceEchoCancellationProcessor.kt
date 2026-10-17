package com.medplay.app.recording

import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Reference-based acoustic echo cancellation for player voice.
 *
 * AEC3 owns its own render history and delay estimator. Clean playback frames
 * must therefore be analyzed as they arrive, before the matching near-end
 * microphone frame is processed. Microphone frames may wait briefly when the
 * playback-capture thread is late so the far-end reference is not starved by
 * thread scheduling.
 */
class ReferenceEchoCancellationProcessor : AutoCloseable {

    data class Metrics(
        val echoReturnLoss: Double,
        val echoReturnLossEnhancement: Double,
        val delayMs: Int,
        val renderFrames: Long,
        val captureFrames: Long,
        val droppedRenderFrames: Long,
        val resets: Int,
        val renderCaptureSkewMs: Int,
    )

    private data class CaptureFrame(
        val startUs: Long,
        val samples: FloatArray,
    )

    private var config: Aec3Config? = null
    private var environment: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echoControl: Aec3EchoControl? = null
    private var renderBuffer: Aec3AudioBuffer? = null
    private var captureBuffer: Aec3AudioBuffer? = null

    private val renderFrame = FloatArray(FRAME_SAMPLES)
    private var renderFill = 0
    private var renderFrameStartUs = Long.MIN_VALUE

    private val captureFrame = FloatArray(FRAME_SAMPLES)
    private var captureFill = 0
    private var captureFrameStartUs = Long.MIN_VALUE
    private val pendingCaptureFrames = ArrayDeque<CaptureFrame>()

    private var captureSilenced = false
    private var renderFrames = 0L
    private var captureFrames = 0L
    private var droppedRenderFrames = 0L
    private var resets = 0
    private var lastAnalyzedRenderStartUs = Long.MIN_VALUE
    private var lastCaptureStartUs = Long.MIN_VALUE

    init {
        createEngine()
    }

    /**
     * Adds clean playback PCM as far-end reference.
     *
     * AudioPlaybackCapture and microphone timestamps share BOOTTIME. Timestamps
     * are retained for ordering/diagnostics only; they are not converted into
     * AEC3's optional audio-buffer-delay value because that API describes I/O
     * buffering, not the acoustic echo-path delay.
     */
    @Synchronized
    fun offerRenderPcm(
        source: ByteBuffer,
        bytesRead: Int,
        firstFrameStartUs: Long,
        channelCount: Int,
    ) {
        if (captureSilenced || bytesRead <= 0 || channelCount <= 0) return

        val control = echoControl ?: return
        val target = renderBuffer ?: return
        val pcm = source.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(0)
        pcm.limit(bytesRead.coerceAtMost(pcm.capacity()))

        val bytesPerInputFrame = channelCount * BYTES_PER_SAMPLE
        var inputFrameIndex = 0L

        while (pcm.remaining() >= bytesPerInputFrame) {
            var mixed = 0
            repeat(channelCount) {
                mixed += pcm.short.toInt()
            }

            if (renderFill == 0) {
                renderFrameStartUs =
                    firstFrameStartUs + framesToUs(inputFrameIndex)
            }
            renderFrame[renderFill++] =
                (mixed / channelCount).toFloat()

            if (renderFill == FRAME_SAMPLES) {
                if (
                    lastAnalyzedRenderStartUs != Long.MIN_VALUE &&
                    renderFrameStartUs <= lastAnalyzedRenderStartUs
                ) {
                    droppedRenderFrames++
                } else {
                    target.writeChannel(0, renderFrame)
                    control.analyzeRender(target)
                    lastAnalyzedRenderStartUs = renderFrameStartUs
                    renderFrames++
                }
                renderFill = 0
                renderFrameStartUs = Long.MIN_VALUE
            }
            inputFrameIndex++
        }
    }

    /**
     * Queues microphone PCM in 10 ms frames and processes frames once the clean
     * playback timeline has caught up. The bounded wait handles scheduling lag
     * between the two AudioRecord threads without changing media timestamps.
     */
    @Synchronized
    fun processCaptureMonoPcm(
        source: ByteBuffer,
        bytesRead: Int,
        firstFrameStartUs: Long,
    ): ByteArray {
        if (captureSilenced) {
            return ByteArray(bytesRead.coerceAtLeast(0))
        }

        if (echoControl == null || captureBuffer == null) {
            return copyPcm(source, bytesRead)
        }

        val pcm = source.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(0)
        pcm.limit(bytesRead.coerceAtMost(pcm.capacity()))

        var sampleIndex = 0L
        while (pcm.remaining() >= BYTES_PER_SAMPLE) {
            if (captureFill == 0) {
                captureFrameStartUs =
                    firstFrameStartUs + framesToUs(sampleIndex)
            }
            captureFrame[captureFill++] = pcm.short.toFloat()
            sampleIndex++

            if (captureFill == FRAME_SAMPLES) {
                pendingCaptureFrames.addLast(
                    CaptureFrame(
                        startUs = captureFrameStartUs,
                        samples = captureFrame.copyOf(),
                    )
                )
                captureFill = 0
                captureFrameStartUs = Long.MIN_VALUE
            }
        }

        return drainReadyCaptureFrames(force = false)
    }

    /**
     * Drains complete microphone frames still waiting for render catch-up.
     * Used at recording shutdown so the bounded scheduling wait never truncates
     * the end of the voice track.
     */
    @Synchronized
    fun flushCapture(): ByteArray {
        return drainReadyCaptureFrames(force = true)
    }

    /**
     * Android may silence this AudioRecord while a game owns microphone input.
     * Stop adapting to render-only history and recreate AEC state when voice
     * capture returns.
     */
    @Synchronized
    fun setCaptureSilenced(silenced: Boolean) {
        if (captureSilenced == silenced) return
        captureSilenced = silenced
        pendingCaptureFrames.clear()
        renderFill = 0
        renderFrameStartUs = Long.MIN_VALUE
        captureFill = 0
        captureFrameStartUs = Long.MIN_VALUE
        lastAnalyzedRenderStartUs = Long.MIN_VALUE
        lastCaptureStartUs = Long.MIN_VALUE

        if (!silenced) {
            destroyEngine()
            createEngine()
            resets++
        }
    }

    @Synchronized
    fun metrics(): Metrics {
        val current = runCatching { echoControl?.getMetrics() }.getOrNull()
        return Metrics(
            echoReturnLoss = current?.echoReturnLoss ?: 0.0,
            echoReturnLossEnhancement =
                current?.echoReturnLossEnhancement ?: 0.0,
            delayMs = current?.delayMs ?: 0,
            renderFrames = renderFrames,
            captureFrames = captureFrames,
            droppedRenderFrames = droppedRenderFrames,
            resets = resets,
            renderCaptureSkewMs =
                if (
                    lastCaptureStartUs != Long.MIN_VALUE &&
                    lastAnalyzedRenderStartUs != Long.MIN_VALUE
                ) {
                    ((lastCaptureStartUs - lastAnalyzedRenderStartUs) / 1_000L)
                        .toInt()
                } else {
                    0
                },
        )
    }

    @Synchronized
    override fun close() {
        pendingCaptureFrames.clear()
        destroyEngine()
        renderFill = 0
        captureFill = 0
        renderFrameStartUs = Long.MIN_VALUE
        captureFrameStartUs = Long.MIN_VALUE
        lastAnalyzedRenderStartUs = Long.MIN_VALUE
        lastCaptureStartUs = Long.MIN_VALUE
    }

    private fun drainReadyCaptureFrames(force: Boolean): ByteArray {
        val control = echoControl ?: return ByteArray(0)
        val target = captureBuffer ?: return ByteArray(0)
        if (pendingCaptureFrames.isEmpty()) return ByteArray(0)

        val maxBytes =
            pendingCaptureFrames.size * FRAME_SAMPLES * BYTES_PER_SAMPLE
        val output = ByteBuffer.allocate(maxBytes)
            .order(ByteOrder.nativeOrder())

        while (pendingCaptureFrames.isNotEmpty()) {
            val frame = pendingCaptureFrames.first()
            val renderCaughtUp =
                lastAnalyzedRenderStartUs != Long.MIN_VALUE &&
                    lastAnalyzedRenderStartUs + RENDER_CLOCK_TOLERANCE_US >=
                    frame.startUs
            val waitBoundReached =
                pendingCaptureFrames.size > MAX_CAPTURE_WAIT_FRAMES

            if (!force && !renderCaughtUp && !waitBoundReached) {
                break
            }

            pendingCaptureFrames.removeFirst()
            lastCaptureStartUs = frame.startUs

            target.writeChannel(0, frame.samples)
            control.analyzeCapture(target)
            control.processCapture(target, false)

            val clean = target.readChannel(0)
            for (sample in clean) {
                output.putShort(
                    sample
                        .coerceIn(
                            Short.MIN_VALUE.toFloat(),
                            Short.MAX_VALUE.toFloat(),
                        )
                        .toInt()
                        .toShort()
                )
            }
            captureFrames++
        }

        return output.array().copyOf(output.position())
    }

    private fun createEngine() {
        val newConfig = createAec3Config().apply {
            // This is only AEC3's initial delay prior. The live echo-path delay
            // continues to be estimated by AEC3 from the render/capture signals.
            setDelayDefaultDelay(DEFAULT_DELAY_PRIOR_MS)
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(false)
        }
        check(newConfig.validate()) { "Invalid WebRTC AEC3 configuration" }

        val newEnvironment = createAec3Environment()
        val newFactory = createAec3FactoryWithConfig(newConfig)
        val newControl = createAec3EchoControl(
            factory = newFactory,
            env = newEnvironment,
            sampleRate = SAMPLE_RATE,
            renderChannels = 1,
            captureChannels = 1,
        )

        config = newConfig
        environment = newEnvironment
        factory = newFactory
        echoControl = newControl
        renderBuffer = createAec3AudioBuffer(SAMPLE_RATE, 1)
        captureBuffer = createAec3AudioBuffer(SAMPLE_RATE, 1)
    }

    private fun destroyEngine() {
        runCatching { captureBuffer?.close() }
        captureBuffer = null
        runCatching { renderBuffer?.close() }
        renderBuffer = null
        runCatching { echoControl?.close() }
        echoControl = null
        runCatching { factory?.close() }
        factory = null
        runCatching { environment?.close() }
        environment = null
        runCatching { config?.close() }
        config = null
    }

    private fun copyPcm(source: ByteBuffer, bytesRead: Int): ByteArray {
        val duplicate = source.duplicate()
        duplicate.position(0)
        duplicate.limit(bytesRead.coerceAtMost(duplicate.capacity()))
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun framesToUs(frames: Long): Long {
        return frames * 1_000_000L / SAMPLE_RATE
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100

        private const val BYTES_PER_SAMPLE = 2
        private const val RENDER_CLOCK_TOLERANCE_US = 2_000L
        private const val MAX_CAPTURE_WAIT_FRAMES = 10
        private const val DEFAULT_DELAY_PRIOR_MS = 25
    }
}
