package com.medplay.app.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer

class ClipTrimExporter(
    private val context: Context,
) {

    data class Result(
        val uri: Uri,
        val displayName: String,
        val requestedStartUs: Long,
        val actualStartUs: Long,
        val endUs: Long,
        val durationUs: Long,
        val audioTrackCount: Int,
    )

    fun export(
        sourceUri: Uri,
        requestedStartUs: Long,
        requestedEndUs: Long,
    ): Result {
        require(requestedEndUs > requestedStartUs)

        val actualStartUs = findPreviousVideoSync(
            sourceUri = sourceUri,
            requestedStartUs = requestedStartUs,
        )
        val endUs = requestedEndUs.coerceAtLeast(actualStartUs + 1L)
        val displayName = "MedPlay_Edit_" + System.currentTimeMillis() + ".mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MedPlay")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val destination = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Unable to create edited MediaStore item")

        var audioTrackCount = 0
        try {
            resolver.openFileDescriptor(destination, "w")?.use { pfd ->
                val extractor = MediaExtractor()
                val muxer = MediaMuxer(
                    pfd.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                var muxerStarted = false

                try {
                    extractor.setDataSource(context, sourceUri, null)

                    readRotationDegrees(sourceUri)?.let { rotation ->
                        if (rotation in setOf(0, 90, 180, 270)) {
                            muxer.setOrientationHint(rotation)
                        }
                    }

                    val outputTracks = IntArray(extractor.trackCount) { -1 }
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(
                            android.media.MediaFormat.KEY_MIME
                        ).orEmpty()
                        if (
                            mime.startsWith("video/") ||
                            mime.startsWith("audio/")
                        ) {
                            outputTracks[track] = muxer.addTrack(format)
                            extractor.selectTrack(track)
                            if (mime.startsWith("audio/")) {
                                audioTrackCount += 1
                            }
                        }
                    }
                    check(outputTracks.any { it >= 0 }) {
                        "No compatible tracks found"
                    }

                    muxer.start()
                    muxerStarted = true
                    extractor.seekTo(
                        actualStartUs,
                        MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                    )

                    var buffer = ByteBuffer.allocateDirect(
                        INITIAL_BUFFER_BYTES
                    )
                    val info = MediaCodec.BufferInfo()
                    val lastPts = LongArray(extractor.trackCount) {
                        Long.MIN_VALUE
                    }

                    while (true) {
                        val track = extractor.sampleTrackIndex
                        if (track < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs > endUs) break

                        if (sampleTimeUs < actualStartUs) {
                            extractor.advance()
                            continue
                        }

                        val outputTrack = outputTracks[track]
                        if (outputTrack < 0) {
                            extractor.advance()
                            continue
                        }

                        val announced = extractor.sampleSize
                        check(announced <= MAX_SAMPLE_BYTES) {
                            "Encoded sample is unreasonably large"
                        }
                        if (announced > buffer.capacity()) {
                            buffer = ByteBuffer.allocateDirect(
                                announced.toInt()
                            )
                        }

                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break

                        val ptsUs = sampleTimeUs - actualStartUs
                        if (ptsUs > lastPts[track]) {
                            info.set(
                                0,
                                size,
                                ptsUs,
                                extractor.sampleFlags,
                            )
                            muxer.writeSampleData(
                                outputTrack,
                                buffer,
                                info,
                            )
                            lastPts[track] = ptsUs
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                    if (muxerStarted) {
                        runCatching { muxer.stop() }
                    }
                    runCatching { muxer.release() }
                }
            } ?: error("Unable to open edited MediaStore item")

            val updated = resolver.update(
                destination,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish edited clip" }

            return Result(
                uri = destination,
                displayName = displayName,
                requestedStartUs = requestedStartUs,
                actualStartUs = actualStartUs,
                endUs = endUs,
                durationUs = (endUs - actualStartUs).coerceAtLeast(0L),
                audioTrackCount = audioTrackCount,
            )
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun findPreviousVideoSync(
        sourceUri: Uri,
        requestedStartUs: Long,
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(android.media.MediaFormat.KEY_MIME)
                    .orEmpty()
                    .startsWith("video/")
            } ?: error("No video track found")

            extractor.selectTrack(videoTrack)
            extractor.seekTo(
                requestedStartUs.coerceAtLeast(0L),
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
            )
            return extractor.sampleTime.coerceAtLeast(0L)
        } finally {
            extractor.release()
        }
    }

    private fun readRotationDegrees(sourceUri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val INITIAL_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_SAMPLE_BYTES = 64L * 1024L * 1024L
    }
}
