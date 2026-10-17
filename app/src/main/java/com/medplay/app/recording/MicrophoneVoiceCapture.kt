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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Captures the player's microphone into an independent AAC track.
 *
 * If a shared [GameVoiceEchoProcessor] is supplied, raw 10 ms microphone PCM
 * is cleaned with WebRTC AEC3 before it reaches MediaCodec. Android's native
 * AcousticEchoCanceler is then deliberately disabled so two adaptive echo
 * cancellers do not fight each other.
 */
class MicrophoneVoiceCapture(
    private val context: Context,
    private val consumer: InternalAudioCapture.EncodedAudioConsumer,
    private val echoProcessor: GameVoiceEchoProcessor? = null,
    private val onSilencedChanged: (Boolean) -> Unit = {},
    private val onPcmPeak: (Int) -> Unit = {},
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

            if (echoProcessor == null) {
                val aecAvailable = AcousticEchoCanceler.isAvailable()
                if (aecAvailable) {
                    echoCanceler = runCatching {
                        AcousticEchoCanceler.create(createdRecord.audioSessionId)
                    }.getOrNull()
                }
                val aecEnabled = runCatching {
                    echoCanceler?.setEnabled(true)
                    echoCanceler?.enabled == true
                }.getOrDefault(false)
                onEchoCancellationState(aecAvailable, aecEnabled)
            } else {
                // Software AEC3 owns echo cancellation for this path.
                onEchoCancellationState(false, false)
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
        echoProcessor?.setMicrophoneSilenced(silenced)
        onSilencedChanged(silenced)
    }

    private fun runLoop(
        record: AudioRecord,
        encoder: MediaCodec,
    ) {
        val outputInfo = MediaCodec.BufferInfo()
        val frameClock = AudioRecordFrameClock(SAMPLE_RATE)
        val pcmFrame = ByteArray(AEC_FRAME_BYTES)
        var captureStartedPublished = false
        var nextSignalProbeFrame = 0L
        var framesSubmitted = 0L
        var lastPtsUs = 0L

        while (running.get()) {
            val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = encoder.getInputBuffer(inputIndex)
                if (input == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, lastPtsUs, 0)
                } else {
                    val bytesRead = readPcmFrame(record, pcmFrame)
                    if (bytesRead < 0) {
                        error("Microphone AudioRecord read failed: $bytesRead")
                    } else if (bytesRead > 0) {
                        val framesRead = bytesRead / BYTES_PER_FRAME
                        val blockTime = frameClock.timestampBlock(
                            record,
                            framesRead,
                        )
                        if (!captureStartedPublished) {
                            consumer.onCaptureStarted(
                                blockTime.absoluteStartUs,
                                SAMPLE_RATE,
                                CHANNEL_COUNT,
                            )
                            captureStartedPublished = true
                        }

                        val rawMono = decodeMonoPcm16(pcmFrame, framesRead)
                        if (framesSubmitted >= nextSignalProbeFrame) {
                            publishPcmPeak(rawMono)
                            nextSignalProbeFrame =
                                framesSubmitted + SAMPLE_RATE / 2L
                        }

                        val outputMono = if (
                            echoProcessor != null &&
                            framesRead == AEC_FRAME_SAMPLES &&
                            bytesRead == AEC_FRAME_BYTES
                        ) {
                            echoProcessor.processNearEnd(
                                monoPcm = rawMono,
                                frameStartUs = blockTime.absoluteStartUs,
                            )
                        } else {
                            rawMono
                        }

                        val outputBytes = encodeMonoPcm16(outputMono)
                        input.clear()
                        check(input.remaining() >= outputBytes.size) {
                            "AAC microphone input buffer smaller than PCM frame"
                        }
                        input.put(outputBytes)

                        lastPtsUs = blockTime.relativePtsUs
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            outputBytes.size,
                            lastPtsUs,
                            0,
                        )
                        framesSubmitted += framesRead
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, lastPtsUs, 0)
                    }
                }
            }

            drainEncoder(encoder, outputInfo)
        }

        val eosIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (eosIndex >= 0) {
            encoder.queueInputBuffer(
                eosIndex,
                0,
                0,
                lastPtsUs + AEC_FRAME_DURATION_US,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }

        repeat(EOS_DRAIN_ATTEMPTS) {
            if (drainEncoder(encoder, outputInfo, DEQUEUE_TIMEOUT_US)) return
        }
    }

    private fun readPcmFrame(
        record: AudioRecord,
        target: ByteArray,
    ): Int {
        var offset = 0
        while (offset < target.size && running.get()) {
            val read = record.read(
                target,
                offset,
                target.size - offset,
                AudioRecord.READ_BLOCKING,
            )
            if (read < 0) return read
            if (read == 0) continue
            offset += read
        }
        return offset
    }

    private fun decodeMonoPcm16(
        bytes: ByteArray,
        framesRead: Int,
    ): ShortArray {
        val samples = ShortArray(framesRead)
        var byteIndex = 0
        for (i in samples.indices) {
            val low = bytes[byteIndex++].toInt() and 0xFF
            val high = bytes[byteIndex++].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    private fun encodeMonoPcm16(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
        var byteIndex = 0
        for (sample in samples) {
            val value = sample.toInt()
            bytes[byteIndex++] = (value and 0xFF).toByte()
            bytes[byteIndex++] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun publishPcmPeak(samples: ShortArray) {
        var peak = 0
        var index = 0
        while (index < samples.size) {
            val sample = abs(samples[index].toInt())
            if (sample > peak) peak = sample
            index += 4
        }
        onPcmPeak(peak)
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
        private const val AEC_FRAME_SAMPLES = GameVoiceEchoProcessor.FRAME_SAMPLES
        private const val AEC_FRAME_BYTES =
            AEC_FRAME_SAMPLES * BYTES_PER_FRAME
        private const val AEC_FRAME_DURATION_US = 10_000L
        private const val PCM_BUFFER_BYTES = 8_192
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val FORCE_STOP_JOIN_TIMEOUT_MS = 250L
        private const val EOS_DRAIN_ATTEMPTS = 20
    }
}
