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
import kotlin.math.roundToInt

/**
 * Shared software echo-cancellation stage for game playback + player voice.
 *
 * WebRTC AEC3 receives:
 * - render/far-end: downmixed PCM from AudioPlaybackCapture;
 * - capture/near-end: PCM from the physical microphone.
 *
 * Both sides operate at 48 kHz in exact 10 ms frames. The internal game track
 * remains untouched; only the microphone track is replaced with AEC3 output.
 */
internal class GameVoiceEchoProcessor private constructor(
    private val onStateChanged: (State) -> Unit,
) : AutoCloseable {

    data class State(
        val enabled: Boolean,
        val renderFrames: Long,
        val captureFrames: Long,
        val processedFrames: Long,
        val echoReturnLossDb: Double? = null,
        val echoReturnLossEnhancementDb: Double? = null,
        val estimatedDelayMs: Int? = null,
    )

    private val lock = Any()

    private var config: Aec3Config? = null
    private var environment: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echoControl: Aec3EchoControl? = null
    private var renderBuffer: Aec3AudioBuffer? = null
    private var captureBuffer: Aec3AudioBuffer? = null

    private var microphoneSilenced = false
    private var closed = false
    private var renderFrames = 0L
    private var captureFrames = 0L
    private var processedFrames = 0L
    private var lastRenderStartUs = Long.MIN_VALUE
    private var lastCaptureStartUs = Long.MIN_VALUE

    init {
        synchronized(lock) {
            initializeLocked()
        }
    }

    fun submitFarEnd(
        stereoPcm: ShortArray,
        frameStartUs: Long,
    ) {
        require(stereoPcm.size == FRAME_SAMPLES * 2) {
            "AEC3 render frame must be 10 ms stereo PCM"
        }

        synchronized(lock) {
            if (closed || microphoneSilenced) return
            val ec = echoControl ?: return
            val buffer = renderBuffer ?: return

            val mono = FloatArray(FRAME_SAMPLES)
            var sourceIndex = 0
            for (i in 0 until FRAME_SAMPLES) {
                val left = stereoPcm[sourceIndex++].toInt()
                val right = stereoPcm[sourceIndex++].toInt()
                mono[i] = ((left + right) * 0.5f) / 32768.0f
            }

            buffer.writeChannel(0, mono)
            ec.analyzeRender(buffer)

            renderFrames++
            lastRenderStartUs = frameStartUs
            publishStateLocked(includeMetrics = false)
        }
    }

    fun processNearEnd(
        monoPcm: ShortArray,
        frameStartUs: Long,
    ): ShortArray {
        require(monoPcm.size == FRAME_SAMPLES) {
            "AEC3 capture frame must be 10 ms mono PCM"
        }

        synchronized(lock) {
            captureFrames++
            lastCaptureStartUs = frameStartUs

            if (closed || microphoneSilenced) {
                publishStateLocked(includeMetrics = false)
                return monoPcm.copyOf()
            }

            val ec = echoControl
            val buffer = captureBuffer
            if (ec == null || buffer == null || renderFrames == 0L) {
                publishStateLocked(includeMetrics = false)
                return monoPcm.copyOf()
            }

            // If render capture disappeared, bypass rather than letting the
            // adaptive filter work from a stale far-end reference.
            if (
                lastRenderStartUs != Long.MIN_VALUE &&
                frameStartUs - lastRenderStartUs > STALE_RENDER_US
            ) {
                publishStateLocked(includeMetrics = false)
                return monoPcm.copyOf()
            }

            val capture = FloatArray(FRAME_SAMPLES)
            for (i in 0 until FRAME_SAMPLES) {
                capture[i] = monoPcm[i] / 32768.0f
            }
            buffer.writeChannel(0, capture)

            ec.analyzeCapture(buffer)
            ec.processCapture(buffer, false)

            val processed = buffer.readChannel(0)
            val result = ShortArray(FRAME_SAMPLES)
            for (i in 0 until FRAME_SAMPLES) {
                result[i] = (
                    processed[i]
                        .coerceIn(-1.0f, 0.9999695f) * 32768.0f
                    ).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            processedFrames++
            publishStateLocked(
                includeMetrics = processedFrames % METRICS_INTERVAL_FRAMES == 0L,
            )
            return result
        }
    }

    /**
     * Android can temporarily silence MedPlay while a game owns microphone
     * priority. Do not continue adapting AEC3 against zeroed capture audio.
     * Recreate the adaptive state when capture becomes available again.
     */
    fun setMicrophoneSilenced(silenced: Boolean) {
        synchronized(lock) {
            if (closed || microphoneSilenced == silenced) return
            microphoneSilenced = silenced
            if (silenced) {
                destroyAecLocked()
            } else {
                initializeLocked()
            }
            publishStateLocked(includeMetrics = false)
        }
    }

    fun snapshot(): State {
        synchronized(lock) {
            return buildStateLocked(includeMetrics = true)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            destroyAecLocked()
            publishStateLocked(includeMetrics = false)
        }
    }

    private fun initializeLocked() {
        if (closed || microphoneSilenced || echoControl != null) return

        val createdConfig = createAec3Config().apply {
            // AEC3 performs its own delay estimation. Keep only a conservative
            // startup hint; do not derive delay from thread scheduling.
            setDelayDefaultDelay(0)
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(true)
            check(validate()) { "Invalid WebRTC AEC3 configuration" }
        }
        val createdEnvironment = createAec3Environment()
        val createdFactory = createAec3FactoryWithConfig(createdConfig)
        val createdEchoControl = createAec3EchoControl(
            createdFactory,
            createdEnvironment,
            SAMPLE_RATE,
            1,
            1,
        )
        val createdRenderBuffer = createAec3AudioBuffer(SAMPLE_RATE, 1)
        val createdCaptureBuffer = createAec3AudioBuffer(SAMPLE_RATE, 1)

        check(createdRenderBuffer.samplesPerChannel == FRAME_SAMPLES) {
            "Unexpected AEC3 render frame size: " +
                createdRenderBuffer.samplesPerChannel
        }
        check(createdCaptureBuffer.samplesPerChannel == FRAME_SAMPLES) {
            "Unexpected AEC3 capture frame size: " +
                createdCaptureBuffer.samplesPerChannel
        }

        config = createdConfig
        environment = createdEnvironment
        factory = createdFactory
        echoControl = createdEchoControl
        renderBuffer = createdRenderBuffer
        captureBuffer = createdCaptureBuffer
    }

    private fun destroyAecLocked() {
        runCatching { renderBuffer?.close() }
        renderBuffer = null
        runCatching { captureBuffer?.close() }
        captureBuffer = null
        runCatching { echoControl?.close() }
        echoControl = null
        runCatching { factory?.close() }
        factory = null
        runCatching { environment?.close() }
        environment = null
        runCatching { config?.close() }
        config = null
        lastRenderStartUs = Long.MIN_VALUE
        lastCaptureStartUs = Long.MIN_VALUE
    }

    private fun publishStateLocked(includeMetrics: Boolean) {
        onStateChanged(buildStateLocked(includeMetrics))
    }

    private fun buildStateLocked(includeMetrics: Boolean): State {
        val metrics = if (includeMetrics) {
            runCatching { echoControl?.getMetrics() }.getOrNull()
        } else {
            null
        }
        return State(
            enabled = !closed && !microphoneSilenced && echoControl != null,
            renderFrames = renderFrames,
            captureFrames = captureFrames,
            processedFrames = processedFrames,
            echoReturnLossDb = metrics?.echoReturnLoss,
            echoReturnLossEnhancementDb = metrics?.echoReturnLossEnhancement,
            estimatedDelayMs = metrics?.delayMs,
        )
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100

        private const val STALE_RENDER_US = 500_000L
        private const val METRICS_INTERVAL_FRAMES = 100L

        fun create(
            onStateChanged: (State) -> Unit = {},
        ): GameVoiceEchoProcessor {
            return GameVoiceEchoProcessor(onStateChanged)
        }
    }
}
