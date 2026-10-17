package com.medplay.app.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures app playback audio permitted by Android and encodes it as AAC-LC.
 *
 * When game + microphone capture are both enabled, each 10 ms PCM frame is
 * also copied to [GameVoiceEchoProcessor] as the far-end/reference signal.
 * The encoded game track itself remains bit-for-bit independent from the AEC
 * output path.
 */
class InternalAudioCapture(
    private val mediaProjection: MediaProjection,
    private val consumer: EncodedAudioConsumer = EncodedAudioConsumer.Discard,
    private val echoProcessor: GameVoiceEchoProcessor? = null,
    private val onFatalError: (Throwable) -> Unit = {},
) {

    interface EncodedAudioConsumer {
        fun onCaptureStarted(monotonicStartUs: Long, sampleRate: Int, channelCount: Int)
        fun onOutputFormat(format: MediaFormat)
        fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)

        object Discard : EncodedAudioConsumer {
            override fun onCaptureStarted(
                monotonicStartUs: Long,
                sampleRate: Int,
                channelCount: Int,
            ) = Unit

            override fun onOutputFormat(format: MediaFormat) = Unit
            override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit
        }
    }

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    fun start() {
        check(!running.get()) { "Internal audio capture already started" }

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord buffer size unavailable" }

        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        var encoderStarted = false

        try {
            val createdRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuffer, PCM_BUFFER_BYTES))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            record = createdRecord

            check(createdRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Playback AudioRecord failed to initialize"
            }

            val codecInfo = selectAacEncoder()
                ?: error("No AAC encoder available")

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
        } catch (error: Throwable) {
            runCatching {
                if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record?.stop()
                }
            }
            if (encoderStarted) {
                runCatching { encoder?.stop() }
            }
            runCatching { encoder?.release() }
            runCatching { record?.release() }
            throw error
        }

        val readyRecord = checkNotNull(record)
        val readyEncoder = checkNotNull(encoder)

        audioRecord = readyRecord
        codec = readyEncoder
        stopping.set(false)
        running.set(true)

        worker = thread(
            start = true,
            isDaemon = true,
            name = "medplay-internal-audio",
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
        val audioWorker = worker

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

        runCatching { record?.release() }
        audioRecord = null

        if (audioWorker?.isAlive == true) {
            audioWorker.join(FORCE_STOP_JOIN_TIMEOUT_MS)
        }
        worker = null
    }

    private fun runLoop(
        record: AudioRecord,
        encoder: MediaCodec,
    ) {
        val outputInfo = MediaCodec.BufferInfo()
        val frameClock = AudioRecordFrameClock(SAMPLE_RATE)
        val pcmFrame = ByteArray(AEC_FRAME_BYTES)
        var captureStartedPublished = false
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
                        error("AudioRecord read failed: $bytesRead")
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

                        if (
                            echoProcessor != null &&
                            framesRead == AEC_FRAME_SAMPLES &&
                            bytesRead == AEC_FRAME_BYTES
                        ) {
                            echoProcessor.submitFarEnd(
                                stereoPcm = decodeStereoPcm16(pcmFrame),
                                frameStartUs = blockTime.absoluteStartUs,
                            )
                        }

                        input.clear()
                        check(input.remaining() >= bytesRead) {
                            "AAC input buffer smaller than PCM frame"
                        }
                        input.put(pcmFrame, 0, bytesRead)
                        lastPtsUs = blockTime.relativePtsUs
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            bytesRead,
                            lastPtsUs,
                            0,
                        )
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

    private fun decodeStereoPcm16(bytes: ByteArray): ShortArray {
        val samples = ShortArray(AEC_FRAME_SAMPLES * CHANNEL_COUNT)
        var byteIndex = 0
        for (i in samples.indices) {
            val low = bytes[byteIndex++].toInt() and 0xFF
            val high = bytes[byteIndex++].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
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
        const val CHANNEL_COUNT = 2
        const val AAC_BITRATE = 128_000

        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val AEC_FRAME_SAMPLES = GameVoiceEchoProcessor.FRAME_SAMPLES
        private const val AEC_FRAME_BYTES =
            AEC_FRAME_SAMPLES * BYTES_PER_FRAME
        private const val AEC_FRAME_DURATION_US = 10_000L
        private const val PCM_BUFFER_BYTES = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val FORCE_STOP_JOIN_TIMEOUT_MS = 250L
        private const val EOS_DRAIN_ATTEMPTS = 20
    }
}
