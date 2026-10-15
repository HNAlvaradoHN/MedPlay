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
        val segmentCount: Int,
    )

    fun export(
        lease: RollingVideoSegmentWriter.SegmentLease,
    ): ExportResult {
        val segments = lease.segments
        require(segments.isNotEmpty())

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
                remuxSegments(segments, descriptor.fileDescriptor)
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
                durationUs = segments.last().sourceEndUs - segments.first().sourceStartUs,
                segmentCount = segments.size,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            lease.close()
        }
    }

    private fun remuxSegments(
        segments: List<RollingVideoSegmentWriter.Segment>,
        outputDescriptor: FileDescriptor,
    ) {
        val firstFormat = readVideoFormat(segments.first())
        val muxer = MediaMuxer(
            outputDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false

        try {
            val outputTrack = muxer.addTrack(firstFormat)
            muxer.start()
            started = true

            val baseSourceUs = segments.first().sourceStartUs
            val maxSegmentBytes = segments.maxOf { it.file.length() }
            val capacity = maxOf(
                DEFAULT_COPY_BUFFER_BYTES.toLong(),
                maxSegmentBytes,
            ).coerceAtMost(MAX_COPY_BUFFER_BYTES.toLong()).toInt()
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()

            segments.forEach { segment ->
                copySegment(
                    segment = segment,
                    muxer = muxer,
                    outputTrack = outputTrack,
                    baseSourceUs = baseSourceUs,
                    buffer = buffer,
                    info = info,
                )
            }
        } finally {
            if (started) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun readVideoFormat(
        segment: RollingVideoSegmentWriter.Segment,
    ): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segment.file.absolutePath)
            val track = findVideoTrack(extractor)
            return extractor.getTrackFormat(track)
        } finally {
            extractor.release()
        }
    }

    private fun copySegment(
        segment: RollingVideoSegmentWriter.Segment,
        muxer: MediaMuxer,
        outputTrack: Int,
        baseSourceUs: Long,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(segment.file.absolutePath)
            val inputTrack = findVideoTrack(extractor)
            extractor.selectTrack(inputTrack)

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val sampleTimeUs = extractor.sampleTime
                check(sampleTimeUs >= 0) { "Invalid sample timestamp" }

                info.set(
                    0,
                    size,
                    (segment.sourceStartUs - baseSourceUs) + sampleTimeUs,
                    extractor.sampleFlags,
                )
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) return index
        }
        error("No video track found in rolling segment")
    }

    companion object {
        private const val DEFAULT_COPY_BUFFER_BYTES = 8 * 1024 * 1024
        private const val MAX_COPY_BUFFER_BYTES = 32 * 1024 * 1024
    }
}
