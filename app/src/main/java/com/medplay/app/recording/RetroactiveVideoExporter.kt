package com.medplay.app.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.FileDescriptor
import java.nio.ByteBuffer

class RetroactiveVideoExporter(
    private val context: Context,
) {

    data class ExportResult(
        val uri: Uri,
        val durationUs: Long,
        val videoSegmentCount: Int,
        val audioSegmentCount: Int,
        val microphoneSegmentCount: Int,
    )

    fun export(
        videoLease: RollingVideoSegmentWriter.SegmentLease,
        audioLease: RollingAudioSegmentWriter.SegmentLease? = null,
        microphoneLease: RollingAudioSegmentWriter.SegmentLease? = null,
    ): ExportResult {
        val videoSegments = videoLease.segments
        require(videoSegments.isNotEmpty())

        validateSegments(
            videoSegments = videoSegments,
            audioSegments = audioLease?.segments.orEmpty(),
            microphoneSegments = microphoneLease?.segments.orEmpty(),
        )

        val displayName = "MedPlay_" + System.currentTimeMillis() + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MedPlay")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Unable to create MediaStore item")

        try {
            resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                remux(
                    videoSegments = videoSegments,
                    audioSegments = audioLease?.segments.orEmpty(),
                    microphoneSegments = microphoneLease?.segments.orEmpty(),
                    outputDescriptor = descriptor.fileDescriptor,
                )
            } ?: error("Unable to open MediaStore destination")

            val updated = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish completed MediaStore clip" }

            return ExportResult(
                uri = uri,
                durationUs = videoSegments.last().sourceEndUs -
                    videoSegments.first().sourceStartUs,
                videoSegmentCount = videoSegments.size,
                audioSegmentCount = audioLease?.segments?.size ?: 0,
                microphoneSegmentCount =
                    microphoneLease?.segments?.size ?: 0,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            microphoneLease?.close()
            audioLease?.close()
            videoLease.close()
        }
    }

    private fun remux(
        videoSegments: List<RollingVideoSegmentWriter.Segment>,
        audioSegments: List<RollingAudioSegmentWriter.Segment>,
        microphoneSegments: List<RollingAudioSegmentWriter.Segment>,
        outputDescriptor: FileDescriptor,
    ) {
        val videoFormat = readTrackFormat(
            videoSegments.first().file.absolutePath,
            "video/",
        )
        validateTrackFormats(
            files = videoSegments.map { it.file.absolutePath },
            mimePrefix = "video/",
            referenceFormat = videoFormat,
        )

        val audioFormat = audioSegments.firstOrNull()?.let {
            readTrackFormat(it.file.absolutePath, "audio/").also { reference ->
                validateTrackFormats(
                    files = audioSegments.map { segment -> segment.file.absolutePath },
                    mimePrefix = "audio/",
                    referenceFormat = reference,
                )
            }
        }
        val microphoneFormat = microphoneSegments.firstOrNull()?.let {
            readTrackFormat(it.file.absolutePath, "audio/").also { reference ->
                validateTrackFormats(
                    files = microphoneSegments.map { segment ->
                        segment.file.absolutePath
                    },
                    mimePrefix = "audio/",
                    referenceFormat = reference,
                )
            }
        }

        val muxer = MediaMuxer(
            outputDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false
        var failure: Throwable? = null

        try {
            val videoTrack = muxer.addTrack(videoFormat)
            // Keep game audio first so existing players continue to select the
            // same default soundtrack. The player's microphone is preserved as
            // a second independent AAC track for MedPlay editing/mixing.
            val audioTrack = audioFormat?.let { muxer.addTrack(it) }
            val microphoneTrack = microphoneFormat?.let { muxer.addTrack(it) }

            muxer.start()
            started = true

            val clipStartUs = videoSegments.first().sourceStartUs
            val clipEndUs = videoSegments.last().sourceEndUs
            var buffer = ByteBuffer.allocateDirect(INITIAL_COPY_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()

            var lastVideoPtsUs = Long.MIN_VALUE
            videoSegments.forEach { segment ->
                val result = copyTrackSegment(
                    filePath = segment.file.absolutePath,
                    mimePrefix = "video/",
                    segmentStartUs = segment.sourceStartUs,
                    clipStartUs = clipStartUs,
                    clipEndUs = clipEndUs,
                    muxer = muxer,
                    outputTrack = videoTrack,
                    buffer = buffer,
                    info = info,
                    lastWrittenPtsUs = lastVideoPtsUs,
                )
                lastVideoPtsUs = result.lastWrittenPtsUs
                buffer = result.buffer
            }

            if (audioTrack != null) {
                var lastAudioPtsUs = Long.MIN_VALUE
                audioSegments.forEach { segment ->
                    val result = copyTrackSegment(
                        filePath = segment.file.absolutePath,
                        mimePrefix = "audio/",
                        segmentStartUs = segment.sourceStartUs,
                        clipStartUs = clipStartUs,
                        clipEndUs = clipEndUs,
                        muxer = muxer,
                        outputTrack = audioTrack,
                        buffer = buffer,
                        info = info,
                        lastWrittenPtsUs = lastAudioPtsUs,
                    )
                    lastAudioPtsUs = result.lastWrittenPtsUs
                    buffer = result.buffer
                }
            }

            if (microphoneTrack != null) {
                var lastMicrophonePtsUs = Long.MIN_VALUE
                microphoneSegments.forEach { segment ->
                    val result = copyTrackSegment(
                        filePath = segment.file.absolutePath,
                        mimePrefix = "audio/",
                        segmentStartUs = segment.sourceStartUs,
                        clipStartUs = clipStartUs,
                        clipEndUs = clipEndUs,
                        muxer = muxer,
                        outputTrack = microphoneTrack,
                        buffer = buffer,
                        info = info,
                        lastWrittenPtsUs = lastMicrophonePtsUs,
                    )
                    lastMicrophonePtsUs = result.lastWrittenPtsUs
                    buffer = result.buffer
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (started) {
                if (failure == null) {
                    muxer.stop()
                } else {
                    runCatching { muxer.stop() }
                }
            }
            runCatching { muxer.release() }
        }
    }

    private data class CopyResult(
        val lastWrittenPtsUs: Long,
        val buffer: ByteBuffer,
    )

    private fun copyTrackSegment(
        filePath: String,
        mimePrefix: String,
        segmentStartUs: Long,
        clipStartUs: Long,
        clipEndUs: Long,
        muxer: MediaMuxer,
        outputTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        lastWrittenPtsUs: Long,
    ): CopyResult {
        val extractor = MediaExtractor()
        var latestWrittenUs = lastWrittenPtsUs
        var workingBuffer = buffer

        try {
            extractor.setDataSource(filePath)
            val inputTrack = findTrack(extractor, mimePrefix)
            extractor.selectTrack(inputTrack)

            while (true) {
                val announcedSize = extractor.sampleSize
                if (announcedSize < 0L) break
                check(announcedSize <= MAX_COPY_SAMPLE_BYTES) {
                    "Encoded sample is unreasonably large: $announcedSize bytes"
                }

                if (announcedSize > workingBuffer.capacity()) {
                    workingBuffer = ByteBuffer.allocateDirect(
                        announcedSize.toInt(),
                    )
                }

                workingBuffer.clear()
                val size = extractor.readSampleData(workingBuffer, 0)
                if (size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) break

                val absoluteUs = segmentStartUs + sampleTimeUs
                if (absoluteUs < clipStartUs) {
                    extractor.advance()
                    continue
                }
                if (absoluteUs > clipEndUs) {
                    break
                }

                val outputPtsUs = absoluteUs - clipStartUs
                check(outputPtsUs > latestWrittenUs) {
                    "Non-monotonic $mimePrefix timestamp during remux"
                }

                info.set(
                    0,
                    size,
                    outputPtsUs,
                    extractor.sampleFlags,
                )
                muxer.writeSampleData(outputTrack, workingBuffer, info)
                latestWrittenUs = outputPtsUs
                extractor.advance()
            }
        } finally {
            extractor.release()
        }

        return CopyResult(
            lastWrittenPtsUs = latestWrittenUs,
            buffer = workingBuffer,
        )
    }

    private fun validateSegments(
        videoSegments: List<RollingVideoSegmentWriter.Segment>,
        audioSegments: List<RollingAudioSegmentWriter.Segment>,
        microphoneSegments: List<RollingAudioSegmentWriter.Segment>,
    ) {
        videoSegments.forEach {
            check(it.file.isFile && it.file.length() > 0L) {
                "Missing or empty video segment: ${it.file.name}"
            }
            check(videoSegmentStartsWithSyncSample(it.file.absolutePath)) {
                "Video segment does not start with a sync frame: ${it.file.name}"
            }
        }
        ClipTimelineValidator.validateOrderedNonOverlapping(
            videoSegments.map {
                ClipTimelineValidator.Range(it.sourceStartUs, it.sourceEndUs)
            }
        )

        if (audioSegments.isNotEmpty()) {
            audioSegments.forEach {
                check(it.file.isFile && it.file.length() > 0L) {
                    "Missing or empty audio segment: ${it.file.name}"
                }
            }
            ClipTimelineValidator.validateOrderedNonOverlapping(
                audioSegments.map {
                    ClipTimelineValidator.Range(it.sourceStartUs, it.sourceEndUs)
                }
            )
        }

        if (microphoneSegments.isNotEmpty()) {
            microphoneSegments.forEach {
                check(it.file.isFile && it.file.length() > 0L) {
                    "Missing or empty microphone segment: ${it.file.name}"
                }
            }
            ClipTimelineValidator.validateOrderedNonOverlapping(
                microphoneSegments.map {
                    ClipTimelineValidator.Range(it.sourceStartUs, it.sourceEndUs)
                }
            )
        }
    }

    private fun videoSegmentStartsWithSyncSample(filePath: String): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            val track = findTrack(extractor, "video/")
            extractor.selectTrack(track)
            return extractor.sampleTime >= 0L &&
                (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
        } finally {
            extractor.release()
        }
    }

    private fun validateTrackFormats(
        files: List<String>,
        mimePrefix: String,
        referenceFormat: MediaFormat,
    ) {
        val reference = TrackFormatSignature.from(referenceFormat)

        files.drop(1).forEach { path ->
            val format = readTrackFormat(path, mimePrefix)
            check(TrackFormatSignature.from(format) == reference) {
                "Track format changed across rolling segments for $mimePrefix"
            }
        }
    }

    private data class TrackFormatSignature(
        val mime: String,
        val width: Int?,
        val height: Int?,
        val sampleRate: Int?,
        val channelCount: Int?,
    ) {
        companion object {
            fun from(format: MediaFormat): TrackFormatSignature {
                fun intOrNull(key: String): Int? {
                    return if (format.containsKey(key)) {
                        format.getInteger(key)
                    } else {
                        null
                    }
                }

                return TrackFormatSignature(
                    mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    width = intOrNull(MediaFormat.KEY_WIDTH),
                    height = intOrNull(MediaFormat.KEY_HEIGHT),
                    sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                )
            }
        }
    }

    private fun readTrackFormat(
        filePath: String,
        mimePrefix: String,
    ): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            val track = findTrack(extractor, mimePrefix)
            return extractor.getTrackFormat(track)
        } finally {
            extractor.release()
        }
    }

    private fun findTrack(
        extractor: MediaExtractor,
        mimePrefix: String,
    ): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        error("No $mimePrefix track found")
    }

    companion object {
        private const val INITIAL_COPY_BUFFER_BYTES = 8 * 1024 * 1024
        private const val MAX_COPY_SAMPLE_BYTES = 64L * 1024L * 1024L
    }
}
