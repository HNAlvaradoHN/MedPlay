package com.medplay.app.recording

object ClipTimelineValidator {

    data class Range(
        val startUs: Long,
        val endUs: Long,
    )

    fun validateOrderedNonOverlapping(ranges: List<Range>) {
        require(ranges.isNotEmpty()) { "Clip requires at least one segment" }

        var previousEndUs = Long.MIN_VALUE
        ranges.forEachIndexed { index, range ->
            require(range.startUs >= 0L) { "Negative segment start at index $index" }
            require(range.endUs >= range.startUs) {
                "Invalid segment range at index $index"
            }
            require(range.startUs > previousEndUs) {
                "Segments are not strictly ordered/non-overlapping at index $index"
            }
            previousEndUs = range.endUs
        }
    }
}
