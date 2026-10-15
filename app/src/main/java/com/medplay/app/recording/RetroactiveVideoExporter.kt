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
    )

    fun export(
        videoLease: RollingVideoSegmentWriter.SegmentLease,
        audioLease: RollingAudioSegmentWriter.SegmentLease? = null,
    ): ExportResult {
        val videoSegments = videoLease.segments
        require(videoSegments.isNotEmpty())

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
                    outputDescriptor = descriptor.fileDescriptor,
                )
            } ?: error("Unable to open MediaStore destination")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )

            return ExportResult(
                uri = uri,
                durationUs = videoSegments.last().sourceEndUs -
                    videoSegments.first().sourceStartUs,
                videoSegmentCount = videoSegments.size,
                audioSegmentCount = audioLease?.segments?.size ?: 0,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            audioLease?.close()
            videoLease.close()
        }
    }

    private fun remux(
        videoSegments: List<RollingVideoSegmentWriter.Segment>,
        audioSegments: List<RollingAudioSegmentWriter.Segment>,
        outputDescriptor: FileDescriptor,
    ) {
        val videoFormat = readTrackFormat(
            videoSegments.first().file.absolutePath,
            "video/",
        )
        val audioFormat = audioSegments.firstOrNull()?.let {
            readTrackFormat(it.file.absolutePath, "audio/")
        }

        val muxer = MediaMuxer(
            outputDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false

        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = audioFormat?.let { muxer.addTrack(it) }

            muxer.start()
            started = true

            val clipStartUs = videoSegments.first().sourceStartUs
            val clipEndUs = videoSegments.last().sourceEndUs
            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()

            videoSegments.forEach { segment ->
                copyTrackSegment(
                    filePath = segment.file.absolutePath,
                    mimePrefix = "video/",
                    segmentStartUs = segment.sourceStartUs,
                    clipStartUs = clipStartUs,
                    clipEndUs = clipEndUs,
                    muxer = muxer,
                    outputTrack = videoTrack,
                    buffer = buffer,
                    info = info,
                )
            }

            if (audioTrack != null) {
                audioSegments.forEach { segment ->
                    copyTrackSegment(
                        filePath = segment.file.absolutePath,
                        mimePrefix = "audio/",
                        segmentStartUs = segment.sourceStartUs,
                        clipStartUs = clipStartUs,
                        clipEndUs = clipEndUs,
                        muxer = muxer,
                        outputTrack = audioTrack,
                        buffer = buffer,
                        info = info,
                    )
                }
            }
        } finally {
            if (started) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

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
    ) {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(filePath)
            val inputTrack = findTrack(extractor, mimePrefix)
            extractor.selectTrack(inputTrack)

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                check(size <= buffer.capacity()) {
                    "Encoded sample exceeds remux buffer"
                }

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

                info.set(
                    0,
                    size,
                    absoluteUs - clipStartUs,
                    extractor.sampleFlags,
                )
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.release()
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
        private const val COPY_BUFFER_BYTES = 8 * 1024 * 1024
    }
}
