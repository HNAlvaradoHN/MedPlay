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

/**
 * Software acoustic echo cancellation for player voice capture.
 *
 * Internal playback PCM is supplied as the far-end/render reference and
 * microphone PCM is supplied as the near-end/capture signal. AEC3 processes
 * fixed 10 ms mono frames at 48 kHz before the microphone signal reaches AAC.
 *
 * This class deliberately owns no AudioRecord/MediaCodec objects. That keeps
 * audio capture policy separate from DSP and lets CaptureService reset AEC
 * state when Android temporarily silences MedPlay's microphone client.
 */
class ReferenceEchoCancellationProcessor : AutoCloseable {

    data class Metrics(
        val echoReturnLoss: Double,
        val echoReturnLossEnhancement: Double,
        val delayMs: Int,
        val renderFrames: Long,
        val captureFrames: Long,
        val resets: Int,
    )

    private var config: Aec3Config? = null
    private var environment: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echoControl: Aec3EchoControl? = null
    private var renderBuffer: Aec3AudioBuffer? = null
    private var captureBuffer: Aec3AudioBuffer? = null

    private val renderFrame = FloatArray(FRAME_SAMPLES)
    private var renderFill = 0

    private val captureFrame = FloatArray(FRAME_SAMPLES)
    private var captureFill = 0

    private var captureSilenced = false
    private var renderFrames = 0L
    private var captureFrames = 0L
    private var resets = 0

    init {
        createEngine()
    }

    @Synchronized
    fun offerRenderStereoPcm(
        source: ByteBuffer,
        bytesRead: Int,
    ) {
        if (captureSilenced) return

        val control = echoControl ?: return
        val target = renderBuffer ?: return
        val pcm = source.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(0)
        pcm.limit(bytesRead.coerceAtMost(pcm.capacity()))

        while (pcm.remaining() >= BYTES_PER_STEREO_FRAME) {
            val left = pcm.short.toInt()
            val right = pcm.short.toInt()
            renderFrame[renderFill++] = ((left + right) / 2).toFloat()

            if (renderFill == FRAME_SAMPLES) {
                target.writeChannel(0, renderFrame)
                control.analyzeRender(target)
                renderFrames++
                renderFill = 0
            }
        }
    }

    /**
     * Processes arbitrary mono PCM input and returns only complete 10 ms AEC3
     * frames. At most 9.99 ms can remain buffered between calls.
     */
    @Synchronized
    fun processCaptureMonoPcm(
        source: ByteBuffer,
        bytesRead: Int,
    ): ByteArray {
        if (captureSilenced) {
            // Preserve the microphone track timeline while Android gives the
            // game exclusive input priority. Encoding silence keeps later
            // voice samples synchronized with the game/video instead of
            // collapsing the silenced interval.
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
            while (pcm.remaining() >= BYTES_PER_SAMPLE) {
                captureFrame[captureFill++] = pcm.short.toFloat()
            }
            return ByteArray(0)
        }

        val output = ByteBuffer
            .allocate(completeFrames * FRAME_SAMPLES * BYTES_PER_SAMPLE)
            .order(ByteOrder.nativeOrder())

        while (pcm.remaining() >= BYTES_PER_SAMPLE) {
            captureFrame[captureFill++] = pcm.short.toFloat()

            if (captureFill == FRAME_SAMPLES) {
                target.writeChannel(0, captureFrame)
                control.analyzeCapture(target)
                control.processCapture(target, false)
                val clean = target.readChannel(0)
                for (sample in clean) {
                    output.putShort(
                        sample
                            .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                            .toInt()
                            .toShort()
                    )
                }
                captureFrames++
                captureFill = 0
            }
        }

        return output.array()
    }

    /**
     * Android can silence this AudioRecord while a game owns microphone input.
     * Do not let stale render-only history influence the next active voice
     * period. Both streams are suspended and AEC state is recreated when the
     * microphone becomes available again.
     */
    @Synchronized
    fun setCaptureSilenced(silenced: Boolean) {
        if (captureSilenced == silenced) return
        captureSilenced = silenced
        renderFill = 0
        captureFill = 0

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
            resets = resets,
        )
    }

    @Synchronized
    override fun close() {
        destroyEngine()
        renderFill = 0
        captureFill = 0
    }

    private fun createEngine() {
        val newConfig = createAec3Config().apply {
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(true)
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
        val newRender = createAec3AudioBuffer(SAMPLE_RATE, 1)
        val newCapture = createAec3AudioBuffer(SAMPLE_RATE, 1)

        config = newConfig
        environment = newEnvironment
        factory = newFactory
        echoControl = newControl
        renderBuffer = newRender
        captureBuffer = newCapture
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

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100

        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_STEREO_FRAME = 4
    }
}
