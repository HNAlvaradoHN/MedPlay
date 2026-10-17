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
 * Playback and microphone AudioRecords run on independent threads and can
 * deliver different chunk sizes. Feeding a large playback chunk directly into
 * AEC3 can advance the render side tens of milliseconds ahead of capture.
 *
 * This class therefore timestamps and queues 10 ms render frames. The capture
 * side drains only render frames that have actually occurred by the capture
 * frame time before asking AEC3 to process the microphone frame.
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
    )

    private data class RenderFrame(
        val startUs: Long,
        val samples: FloatArray,
    )

    private var config: Aec3Config? = null
    private var environment: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echoControl: Aec3EchoControl? = null
    private var renderBuffer: Aec3AudioBuffer? = null
    private var captureBuffer: Aec3AudioBuffer? = null

    private val renderQueue = ArrayDeque<RenderFrame>()
    private val renderFrame = FloatArray(FRAME_SAMPLES)
    private var renderFill = 0
    private var renderFrameStartUs = Long.MIN_VALUE

    private val captureFrame = FloatArray(FRAME_SAMPLES)
    private var captureFill = 0
    private var captureFrameStartUs = Long.MIN_VALUE

    private var captureSilenced = false
    private var renderFrames = 0L
    private var captureFrames = 0L
    private var droppedRenderFrames = 0L
    private var resets = 0
    private var lastAnalyzedRenderStartUs = Long.MIN_VALUE

    init {
        createEngine()
    }

    /**
     * Adds clean playback PCM as far-end reference.
     *
     * The callback supplies the BOOTTIME-anchored timestamp of the first PCM
     * frame. Audio is copied into bounded 10 ms mono frames; AEC3 analysis is
     * intentionally deferred until the corresponding microphone time arrives.
     */
    @Synchronized
    fun offerRenderPcm(
        source: ByteBuffer,
        bytesRead: Int,
        firstFrameStartUs: Long,
        channelCount: Int,
    ) {
        if (captureSilenced || bytesRead <= 0 || channelCount <= 0) return

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
                enqueueRenderFrame(
                    RenderFrame(
                        startUs = renderFrameStartUs,
                        samples = renderFrame.copyOf(),
                    )
                )
                renderFill = 0
                renderFrameStartUs = Long.MIN_VALUE
            }
            inputFrameIndex++
        }
    }

    /**
     * Processes microphone PCM and returns only complete 10 ms AEC3 frames.
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

        val control = echoControl ?: return copyPcm(source, bytesRead)
        val target = captureBuffer ?: return copyPcm(source, bytesRead)

        val pcm = source.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(0)
        pcm.limit(bytesRead.coerceAtMost(pcm.capacity()))

        val completeFrames =
            (captureFill + pcm.remaining() / BYTES_PER_SAMPLE) / FRAME_SAMPLES
        if (completeFrames <= 0) {
            var sampleIndex = 0L
            while (pcm.remaining() >= BYTES_PER_SAMPLE) {
                if (captureFill == 0) {
                    captureFrameStartUs =
                        firstFrameStartUs + framesToUs(sampleIndex)
                }
                captureFrame[captureFill++] = pcm.short.toFloat()
                sampleIndex++
            }
            return ByteArray(0)
        }

        val output = ByteBuffer
            .allocate(completeFrames * FRAME_SAMPLES * BYTES_PER_SAMPLE)
            .order(ByteOrder.nativeOrder())

        var sampleIndex = 0L
        while (pcm.remaining() >= BYTES_PER_SAMPLE) {
            if (captureFill == 0) {
                captureFrameStartUs =
                    firstFrameStartUs + framesToUs(sampleIndex)
            }
            captureFrame[captureFill++] = pcm.short.toFloat()
            sampleIndex++

            if (captureFill == FRAME_SAMPLES) {
                drainRenderThrough(captureFrameStartUs)

                target.writeChannel(0, captureFrame)
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
                captureFill = 0
                captureFrameStartUs = Long.MIN_VALUE
            }
        }

        return output.array()
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
        renderQueue.clear()
        renderFill = 0
        renderFrameStartUs = Long.MIN_VALUE
        captureFill = 0
        captureFrameStartUs = Long.MIN_VALUE
        lastAnalyzedRenderStartUs = Long.MIN_VALUE

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
        )
    }

    @Synchronized
    override fun close() {
        renderQueue.clear()
        destroyEngine()
        renderFill = 0
        captureFill = 0
        renderFrameStartUs = Long.MIN_VALUE
        captureFrameStartUs = Long.MIN_VALUE
    }

    private fun enqueueRenderFrame(frame: RenderFrame) {
        while (renderQueue.size >= MAX_RENDER_QUEUE_FRAMES) {
            renderQueue.removeFirst()
            droppedRenderFrames++
        }
        renderQueue.addLast(frame)
    }

    private fun drainRenderThrough(captureStartUs: Long) {
        val control = echoControl ?: return
        val target = renderBuffer ?: return

        while (renderQueue.isNotEmpty()) {
            val frame = renderQueue.first()
            if (frame.startUs > captureStartUs + RENDER_CLOCK_TOLERANCE_US) {
                break
            }

            renderQueue.removeFirst()
            if (
                lastAnalyzedRenderStartUs != Long.MIN_VALUE &&
                frame.startUs <= lastAnalyzedRenderStartUs
            ) {
                droppedRenderFrames++
                continue
            }

            target.writeChannel(0, frame.samples)
            control.analyzeRender(target)
            lastAnalyzedRenderStartUs = frame.startUs
            renderFrames++
        }
    }

    private fun createEngine() {
        val newConfig = createAec3Config().apply {
            // The wrapper's own live example uses 25 ms as the render/capture
            // buffer delay prior. AEC3 still estimates the acoustic path.
            setDelayDefaultDelay(DEFAULT_AUDIO_BUFFER_DELAY_MS)
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
        newControl.setAudioBufferDelay(DEFAULT_AUDIO_BUFFER_DELAY_MS)

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
        private const val MAX_RENDER_QUEUE_FRAMES = 100
        private const val RENDER_CLOCK_TOLERANCE_US = 2_000L
        private const val DEFAULT_AUDIO_BUFFER_DELAY_MS = 25
    }
}
