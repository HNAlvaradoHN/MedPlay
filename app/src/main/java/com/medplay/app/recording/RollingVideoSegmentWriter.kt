package com.medplay.app.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes already-encoded video samples into short MP4 segments.
 *
 * Segment boundaries are only cut on sync frames so each retained file is
 * independently decodable. No video re-encoding occurs here.
 */
class RollingVideoSegmentWriter(
    private val directory: File,
    private val retentionUs: Long = DEFAULT_RETENTION_US,
    private val targetSegmentUs: Long = DEFAULT_SEGMENT_US,
) : HardwareVideoEncoder.SampleConsumer {

    data class Segment(
        val file: File,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
    )

    private val completed = ArrayDeque<Segment>()
    private val leasedPaths = mutableSetOf<String>()
    private var latestCompletedEndUs = Long.MIN_VALUE

    private var outputFormat: MediaFormat? = null
    private val presentationTimeline = MonotonicPresentationTimeline()
    private var timelineStarted = false
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var currentFile: File? = null
    private var currentStartUs = Long.MIN_VALUE
    private var currentEndUs = Long.MIN_VALUE
    private var sequence = 0L

    init {
        require(retentionUs >= targetSegmentUs)
        directory.mkdirs()
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "mp4" }
            ?.forEach { it.delete() }
    }

    @Synchronized
    override fun onEncoderStarted(monotonicStartUs: Long) {
        presentationTimeline.start(monotonicStartUs)
        timelineStarted = true
    }

    @Synchronized
    override fun onOutputFormat(format: MediaFormat) {
        check(outputFormat == null) { "Encoder output format changed more than once" }
        outputFormat = format
    }

    @Synchronized
    override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return

        if (!timelineStarted) return

        val absolutePtsUs = presentationTimeline.map(info.presentationTimeUs)
        val isSyncFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val activeMuxer = muxer

        if (activeMuxer == null) {
            if (!isSyncFrame) return
            openSegment(absolutePtsUs)
        } else if (
            isSyncFrame &&
            currentStartUs != Long.MIN_VALUE &&
            absolutePtsUs - currentStartUs >= targetSegmentUs
        ) {
            finishCurrentSegment()
            openSegment(absolutePtsUs)
        }

        val destinationMuxer = muxer ?: return
        val normalizedInfo = MediaCodec.BufferInfo().apply {
            set(
                0,
                info.size,
                (absolutePtsUs - currentStartUs).coerceAtLeast(0L),
                info.flags,
            )
        }

        destinationMuxer.writeSampleData(trackIndex, buffer, normalizedInfo)
        currentEndUs = absolutePtsUs
    }

    class SegmentLease internal constructor(
        val segments: List<Segment>,
        private val releaser: () -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            releaser()
        }
    }

    @Synchronized
    fun leaseCompleted(durationUs: Long): SegmentLease? {
        require(durationUs > 0)
        if (completed.isEmpty()) return null

        val latestUs = completed.last().sourceEndUs
        val desiredStartUs = latestUs - durationUs
        val selected = completed
            .filter { it.sourceEndUs >= desiredStartUs }
            .toList()

        if (selected.isEmpty()) return null

        selected.forEach { leasedPaths += it.file.absolutePath }

        return SegmentLease(selected) {
            releaseLease(selected)
        }
    }

    @Synchronized
    fun close() {
        finishCurrentSegment()
    }

    private fun openSegment(sourceStartUs: Long) {
        val format = outputFormat ?: return

        directory.mkdirs()
        val file = File(
            directory,
            "video_%06d.mp4".format(sequence++),
        )

        val newMuxer = MediaMuxer(
            file.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        val newTrack = newMuxer.addTrack(format)
        newMuxer.start()

        muxer = newMuxer
        trackIndex = newTrack
        currentFile = file
        currentStartUs = sourceStartUs
        currentEndUs = sourceStartUs
    }

    private fun finishCurrentSegment() {
        val currentMuxer = muxer
        val file = currentFile
        val startUs = currentStartUs
        val endUs = currentEndUs

        muxer = null
        trackIndex = -1
        currentFile = null
        currentStartUs = Long.MIN_VALUE
        currentEndUs = Long.MIN_VALUE

        if (currentMuxer != null) {
            try {
                currentMuxer.stop()
            } catch (error: Throwable) {
                file?.delete()
                throw error
            } finally {
                runCatching { currentMuxer.release() }
            }
        }

        if (file != null && startUs != Long.MIN_VALUE && endUs >= startUs) {
            completed.addLast(Segment(file, startUs, endUs))
            latestCompletedEndUs = endUs
            trimRetention(endUs)
        } else {
            file?.delete()
        }
    }

    private fun trimRetention(latestUs: Long) {
        val keepFromUs = latestUs - retentionUs

        while (completed.size > 1) {
            val oldest = completed.first()
            if (oldest.sourceEndUs >= keepFromUs) break
            if (oldest.file.absolutePath in leasedPaths) break
            completed.removeFirst()
            oldest.file.delete()
        }
    }

    @Synchronized
    private fun releaseLease(segments: List<Segment>) {
        segments.forEach { leasedPaths.remove(it.file.absolutePath) }
        if (latestCompletedEndUs != Long.MIN_VALUE) {
            trimRetention(latestCompletedEndUs)
        }
    }

    companion object {
        const val DEFAULT_SEGMENT_US = 2_000_000L
        const val DEFAULT_RETENTION_US = 60_000_000L
    }
}
