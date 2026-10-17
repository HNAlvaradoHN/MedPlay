package com.medplay.app.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures the player's microphone into an independent AAC track.
 *
 * The AudioRecord remains active when Android temporarily silences this client
 * because another ordinary app (for example, the game voice chat) wins input
 * priority. That lets Android automatically unsilence MedPlay later without a
 * recorder restart or any attempt to steal microphone priority from the game.
 */
class MicrophoneVoiceCapture(
    private val context: Context,
    private val consumer: InternalAudioCapture.EncodedAudioConsumer,
    private val onSilencedChanged: (Boolean) -> Unit = {},
    private val onPcmPeak: (Int) -> Unit = {},
    private val echoProcessor: ReferenceEchoCancellationProcessor? = null,
    private val onEchoCancellationState: (
        available: Boolean,
        enabled: Boolean,
    ) -> Unit = { _, _ -> },
    private val onFatalError: (Throwable) -> Unit = {},
) {

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var lastSilenced: Boolean? = null

    fun start() {
        check(!running.get()) { "Microphone capture already started" }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Microphone AudioRecord buffer size unavailable" }

        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        var encoderStarted = false
        var callback: AudioManager.AudioRecordingCallback? = null
        var echoCanceler: AcousticEchoCanceler? = null

        try {
            val createdRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuffer, PCM_BUFFER_BYTES))
                .build()
            record = createdRecord

            check(createdRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone AudioRecord failed to initialize"
            }

            if (echoProcessor != null) {
                // Software AEC3 owns echo cancellation. Do not run two
                // adaptive cancellers on the same near-end signal.
                onEchoCancellationState(true, true)
            } else {
                val available = AcousticEchoCanceler.isAvailable()
                if (available) {
                    echoCanceler = runCatching {
                        AcousticEchoCanceler.create(
                            createdRecord.audioSessionId
                        )
                    }.getOrNull()
                }
                val enabled = runCatching {
                    echoCanceler?.setEnabled(true)
                    echoCanceler?.enabled == true
                }.getOrDefault(false)
                onEchoCancellationState(available, enabled)
            }

            val createdCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(
                    configs: MutableList<android.media.AudioRecordingConfiguration>,
                ) {
                    val silenced =
                        createdRecord.activeRecordingConfiguration?.isClientSilenced
                            ?: return
                    publishSilencedState(silenced)
                }
            }
            callback = createdCallback
            createdRecord.registerAudioRecordingCallback(
                context.mainExecutor,
                createdCallback,
            )

            val codecInfo = selectAacEncoder()
                ?: error("No AAC encoder available for microphone")

            val createdEncoder = MediaCodec.createByCodecName(codecInfo.name)
            encoder = createdEncoder
            val encoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNEL_COUNT,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BUFFER_BYTES)
            }

            createdEncoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            createdEncoder.start()
            encoderStarted = true
            createdRecord.startRecording()

            createdRecord.activeRecordingConfiguration?.let {
                publishSilencedState(it.isClientSilenced)
            }
        } catch (error: Throwable) {
            if (record != null && callback != null) {
                runCatching { record.unregisterAudioRecordingCallback(callback) }
            }
            runCatching {
                if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record?.stop()
                }
            }
            if (encoderStarted) {
                runCatching { encoder?.stop() }
            }
            runCatching { encoder?.release() }
            runCatching { echoCanceler?.release() }
            runCatching { record?.release() }
            throw error
        }

        val readyRecord = checkNotNull(record)
        val readyEncoder = checkNotNull(encoder)

        audioRecord = readyRecord
        codec = readyEncoder
        recordingCallback = callback
        acousticEchoCanceler = echoCanceler
        stopping.set(false)
        running.set(true)

        worker = thread(
            start = true,
            isDaemon = true,
            name = "medplay-microphone-voice",
        ) {
            try {
                runLoop(readyRecord, readyEncoder)
            } catch (error: Throwable) {
                if (!stopping.get()) {
                    onFatalError(error)
                }
            } finally {
                running.set(false)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (audioRecord == null && codec == null) return

        stopping.set(true)
        running.set(false)

        val record = audioRecord
        val encoder = codec
        val callback = recordingCallback
        val audioWorker = worker

        if (record != null && callback != null) {
            runCatching { record.unregisterAudioRecordingCallback(callback) }
        }
        recordingCallback = null

        runCatching { record?.stop() }
        audioWorker?.join(STOP_JOIN_TIMEOUT_MS)

        var codecStoppedForcibly = false
        if (audioWorker?.isAlive == true) {
            runCatching { encoder?.stop() }
            codecStoppedForcibly = true
            audioWorker.join(FORCE_STOP_JOIN_TIMEOUT_MS)
        }

        if (!codecStoppedForcibly) {
            runCatching { encoder?.stop() }
        }
        runCatching { encoder?.release() }
        codec = null

        runCatching { acousticEchoCanceler?.release() }
        acousticEchoCanceler = null

        runCatching { record?.release() }
        audioRecord = null

        if (audioWorker?.isAlive == true) {
            audioWorker.join(FORCE_STOP_JOIN_TIMEOUT_MS)
        }
        worker = null
        lastSilenced = null
    }

    private fun publishSilencedState(silenced: Boolean) {
        if (lastSilenced == silenced) return
        lastSilenced = silenced
        echoProcessor?.setCaptureSilenced(silenced)
        onSilencedChanged(silenced)
    }

    private fun runLoop(
        record: AudioRecord,
        encoder: MediaCodec,
    ) {
        val outputInfo = MediaCodec.BufferInfo()
        val rawInput = ByteBuffer.allocateDirect(AEC_FRAME_BYTES)
            .order(ByteOrder.nativeOrder())
        var totalFramesSubmitted = 0L
        var nextSignalProbeFrame = 0L
        var captureStartPublished = false

        while (running.get()) {
            rawInput.clear()
            val bytesRead = record.read(
                rawInput,
                AEC_FRAME_BYTES,
                AudioRecord.READ_BLOCKING,
            )

            if (bytesRead < 0) {
                error("Microphone AudioRecord read failed: $bytesRead")
            }
            if (bytesRead == 0) {
                drainEncoder(encoder, outputInfo)
                continue
            }

            if (!captureStartPublished) {
                consumer.onCaptureStarted(
                    AudioRecordClock.captureStartMonotonicUs(
                        record,
                        SAMPLE_RATE,
                    ),
                    SAMPLE_RATE,
                    CHANNEL_COUNT,
                )
                captureStartPublished = true
            }

            if (totalFramesSubmitted >= nextSignalProbeFrame) {
                publishPcmPeak(rawInput, bytesRead)
                nextSignalProbeFrame =
                    totalFramesSubmitted + SAMPLE_RATE / 2L
            }

            val processed = echoProcessor?.processCaptureMonoPcm(
                rawInput,
                bytesRead,
            ) ?: ByteArray(bytesRead).also { bytes ->
                val copy = rawInput.duplicate()
                copy.position(0)
                copy.limit(bytesRead)
                copy.get(bytes)
            }

            if (processed.isNotEmpty()) {
                var offset = 0
                while (offset < processed.size && running.get()) {
                    val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex < 0) {
                        drainEncoder(encoder, outputInfo)
                        continue
                    }

                    val input = encoder.getInputBuffer(inputIndex)
                    if (input == null) {
                        val ptsUs =
                            totalFramesSubmitted * 1_000_000L / SAMPLE_RATE
                        encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                        continue
                    }

                    input.clear()
                    val chunk = minOf(
                        input.remaining(),
                        processed.size - offset,
                    )
                    input.put(processed, offset, chunk)

                    val ptsUs =
                        totalFramesSubmitted * 1_000_000L / SAMPLE_RATE
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        chunk,
                        ptsUs,
                        0,
                    )
                    totalFramesSubmitted += chunk / BYTES_PER_FRAME
                    offset += chunk
                    drainEncoder(encoder, outputInfo)
                }
            } else {
                drainEncoder(encoder, outputInfo)
            }
        }

        val eosIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (eosIndex >= 0) {
            val ptsUs = totalFramesSubmitted * 1_000_000L / SAMPLE_RATE
            encoder.queueInputBuffer(
                eosIndex,
                0,
                0,
                ptsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }

        repeat(EOS_DRAIN_ATTEMPTS) {
            if (drainEncoder(encoder, outputInfo, DEQUEUE_TIMEOUT_US)) return
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long = 0L,
    ): Boolean {
        var reachedEos = false

        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return reachedEos
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    consumer.onOutputFormat(encoder.outputFormat)
                }
                else -> {
                    if (index < 0) return reachedEos

                    try {
                        val output = encoder.getOutputBuffer(index)
                        if (
                            output != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            consumer.onSample(output.slice(), info)
                        }

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            reachedEos = true
                        }
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }

                    if (reachedEos) return true
                }
            }
        }
    }

    private fun publishPcmPeak(
        input: ByteBuffer,
        bytesRead: Int,
    ) {
        val probe = input.duplicate()
            .order(ByteOrder.nativeOrder())
        probe.position(0)
        probe.limit(bytesRead.coerceAtMost(probe.capacity()))

        var peak = 0
        var sampleIndex = 0
        while (probe.remaining() >= 2) {
            val sample = kotlin.math.abs(probe.short.toInt())
            if (sample > peak) peak = sample

            // Sampling every fourth PCM value is sufficient for diagnostics
            // and keeps this evidence path cheap.
            sampleIndex++
            if (sampleIndex % 4 == 0 && probe.remaining() >= 6) {
                probe.position(probe.position() + 6)
            }
        }
        onPcmPeak(peak)
    }

    private fun selectAacEncoder(): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info ->
                info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
                }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated }
                    .thenBy { it.name }
            )
            .firstOrNull()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val AAC_BITRATE = 96_000

        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val AEC_FRAME_BYTES =
            ReferenceEchoCancellationProcessor.FRAME_SAMPLES *
                BYTES_PER_FRAME
        private const val PCM_BUFFER_BYTES = 8_192
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val FORCE_STOP_JOIN_TIMEOUT_MS = 250L
        private const val EOS_DRAIN_ATTEMPTS = 20
    }
}
