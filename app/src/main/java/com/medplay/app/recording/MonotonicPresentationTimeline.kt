package com.medplay.app.recording

/**
 * Maps an arbitrary monotonically increasing codec PTS domain onto a known
 * monotonic session anchor without assuming the codec starts at zero.
 */
class MonotonicPresentationTimeline {

    private var anchorUs = Long.MIN_VALUE
    private var firstPresentationUs = Long.MIN_VALUE
    private var lastMappedUs = Long.MIN_VALUE

    fun start(anchorUs: Long) {
        require(anchorUs >= 0L)
        this.anchorUs = anchorUs
        firstPresentationUs = Long.MIN_VALUE
        lastMappedUs = Long.MIN_VALUE
    }

    fun map(presentationTimeUs: Long): Long {
        check(anchorUs != Long.MIN_VALUE) { "Timeline not started" }

        if (firstPresentationUs == Long.MIN_VALUE) {
            firstPresentationUs = presentationTimeUs
        }

        val deltaUs = presentationTimeUs - firstPresentationUs
        check(deltaUs >= 0L) { "Presentation timestamp regressed" }

        val mappedUs = anchorUs + deltaUs
        check(lastMappedUs == Long.MIN_VALUE || mappedUs >= lastMappedUs) {
            "Mapped timestamp regressed"
        }
        lastMappedUs = mappedUs
        return mappedUs
    }
}
