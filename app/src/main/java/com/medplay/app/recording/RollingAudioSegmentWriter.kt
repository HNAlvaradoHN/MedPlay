package com.medplay.app.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes already-encoded AAC into short MP4 audio segments on the same
 * monotonic timeline used by video. No audio decode/re-encode occurs here.
 */
class RollingAudioSegmentWriter(
    private val directory: File,
    private val retentionUs: Long = DEFAULT_RETENTION_US,
    private val targetSegmentUs: Long = DEFAULT_SEGMENT_US,
) : InternalAudioCapture.EncodedAudioConsumer {

    data class Segment(
        val file: File,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
    )

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

    private val completed = ArrayDeque<Segment>()
    private val leasedPaths = mutableSetOf<String>()

    private var captureMonotonicStartUs = Long.MIN_VALUE
    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var currentFile: File? = null
    private var currentStartUs = Long.MIN_VALUE
    private var currentEndUs = Long.MIN_VALUE
    private var latestCompletedEndUs = Long.MIN_VALUE
    private var sequence = 0L
    private var paused = false

    init {
        require(retentionUs >= targetSegmentUs)
        directory.mkdirs()
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "mp4" }
            ?.forEach { it.delete() }
    }

    @Synchronized
    override fun onCaptureStarted(
        monotonicStartUs: Long,
        sampleRate: Int,
        channelCount: Int,
    ) {
        captureMonotonicStartUs = monotonicStartUs
    }

    @Synchronized
    override fun onOutputFormat(format: MediaFormat) {
        check(outputFormat == null) { "Audio output format changed more than once" }
        outputFormat = format
    }

    @Synchronized
    override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        if (captureMonotonicStartUs == Long.MIN_VALUE) return
        if (paused) return

        val absolutePtsUs = captureMonotonicStartUs + info.presentationTimeUs

        if (muxer == null) {
            openSegment(absolutePtsUs)
        } else if (
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

    @Synchronized
    fun finalizeActiveSegmentForExport(): Boolean {
        if (muxer == null) return false
        finishCurrentSegment()
        return true
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        if (paused == value) return
        if (value && muxer != null) {
            finishCurrentSegment()
        }
        paused = value
    }

    @Synchronized
    fun leaseRange(startUs: Long, endUs: Long): SegmentLease? {
        if (completed.isEmpty() || endUs <= startUs) return null

        val selected = completed
            .filter { it.sourceEndUs >= startUs && it.sourceStartUs <= endUs }
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
        val file = File(directory, "audio_%06d.mp4".format(sequence++))
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
