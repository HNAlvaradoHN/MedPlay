package com.medplay.app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

class ClipAudioTrackDemuxer(
    private val context: Context,
) {

    data class TrackFile(
        val file: File,
        val sourceTrackIndex: Int,
    )

    fun extract(
        sourceUri: Uri,
        trimStartUs: Long,
        trimEndUs: Long,
    ): List<TrackFile> {
        require(trimEndUs > trimStartUs)

        val probe = MediaExtractor()
        val audioTracks = mutableListOf<Int>()
        try {
            probe.setDataSource(context, sourceUri, null)
            for (index in 0 until probe.trackCount) {
                val mime = probe.getTrackFormat(index)
                    .getString(android.media.MediaFormat.KEY_MIME)
                    .orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTracks += index
                }
            }
        } finally {
            probe.release()
        }

        return audioTracks.mapIndexed { ordinal, trackIndex ->
            val file = File(
                context.cacheDir,
                "medplay_audio_track_${System.currentTimeMillis()}_$ordinal.mp4",
            )
            runCatching { file.delete() }
            extractOne(
                sourceUri = sourceUri,
                sourceTrackIndex = trackIndex,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
                destination = file,
            )
            TrackFile(file, trackIndex)
        }
    }

    private fun extractOne(
        sourceUri: Uri,
        sourceTrackIndex: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        destination: File,
    ) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(
            destination.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false

        try {
            extractor.setDataSource(context, sourceUri, null)
            val format = extractor.getTrackFormat(sourceTrackIndex)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            started = true

            extractor.selectTrack(sourceTrackIndex)
            extractor.seekTo(
                trimStartUs.coerceAtLeast(0L),
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
            )

            var buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var lastPtsUs = Long.MIN_VALUE

            while (true) {
                val sampleTrack = extractor.sampleTrackIndex
                if (sampleTrack < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L || sampleTimeUs > trimEndUs) break

                if (
                    sampleTrack != sourceTrackIndex ||
                    sampleTimeUs < trimStartUs
                ) {
                    extractor.advance()
                    continue
                }

                val announced = extractor.sampleSize
                check(announced <= MAX_SAMPLE_BYTES) {
                    "Encoded audio sample is unreasonably large"
                }
                if (announced > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect(announced.toInt())
                }

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val outputPtsUs = sampleTimeUs - trimStartUs
                if (outputPtsUs > lastPtsUs) {
                    info.set(
                        0,
                        size,
                        outputPtsUs,
                        extractor.sampleFlags,
                    )
                    muxer.writeSampleData(outputTrack, buffer, info)
                    lastPtsUs = outputPtsUs
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
            if (started) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    companion object {
        private const val INITIAL_BUFFER_BYTES = 512 * 1024
        private const val MAX_SAMPLE_BYTES = 16L * 1024L * 1024L
    }
}
