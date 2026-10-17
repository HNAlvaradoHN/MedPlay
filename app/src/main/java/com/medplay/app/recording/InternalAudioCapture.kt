package com.medplay.app.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures app playback audio permitted by Android and encodes it as AAC-LC.
 *
 * The normal capture path never mixes or resamples audio on the CPU. PCM from
 * AudioRecord is fed directly into MediaCodec. Hardware AAC is preferred when
 * available; software AAC is allowed as a low-cost fallback.
 */
class InternalAudioCapture(
    private val mediaProjection: MediaProjection,
    private val consumer: EncodedAudioConsumer = EncodedAudioConsumer.Discard,
    private val onPcmReference: (
        buffer: ByteBuffer,
        bytesRead: Int,
        firstFrameStartUs: Long,
        channelCount: Int,
    ) -> Unit = { _, _, _, _ -> },
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

            val monotonicStartUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            consumer.onCaptureStarted(monotonicStartUs, SAMPLE_RATE, CHANNEL_COUNT)
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
                runLoop(
                    readyRecord,
                    readyEncoder,
                    monotonicStartUs,
                )
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
        captureStartUs: Long,
    ) {
        val outputInfo = MediaCodec.BufferInfo()
        val captureClock = AudioCaptureClock(
            sampleRate = SAMPLE_RATE,
            captureStartUs = captureStartUs,
        )
        var totalFramesSubmitted = 0L

        while (running.get()) {
            val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = encoder.getInputBuffer(inputIndex)
                if (input != null) {
                    input.clear()
                    val bytesRead = record.read(
                        input,
                        minOf(input.remaining(), PCM_BUFFER_BYTES),
                        AudioRecord.READ_BLOCKING,
                    )

                    if (bytesRead < 0) {
                        error("AudioRecord read failed: $bytesRead")
                    } else if (bytesRead > 0) {
                        val framesRead = bytesRead / BYTES_PER_FRAME
                        val timing = captureClock.timingForRead(
                            record = record,
                            firstFramePosition = totalFramesSubmitted,
                            framesRead = framesRead,
                        )
                        onPcmReference(
                            input.duplicate(),
                            bytesRead,
                            timing.absoluteStartUs,
                            CHANNEL_COUNT,
                        )
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            bytesRead,
                            timing.presentationTimeUs,
                            0,
                        )
                        totalFramesSubmitted += framesRead
                    } else {
                        val ptsUs = captureClock.ptsForFramePosition(
                            totalFramesSubmitted
                        )
                        encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    }
                } else {
                    val ptsUs = captureClock.ptsForFramePosition(
                        totalFramesSubmitted
                    )
                    encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                }
            }

            drainEncoder(encoder, outputInfo)
        }

        val eosIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (eosIndex >= 0) {
            val ptsUs = captureClock.ptsForFramePosition(
                totalFramesSubmitted
            )
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
        private const val PCM_BUFFER_BYTES = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val FORCE_STOP_JOIN_TIMEOUT_MS = 250L
        private const val EOS_DRAIN_ATTEMPTS = 20
    }
}
