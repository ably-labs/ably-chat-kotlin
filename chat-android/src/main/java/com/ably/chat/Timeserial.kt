package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo

/**
 * Represents a parsed timeserial.
 */
data class Timeserial(
    /**
     * The series ID of the timeserial.
     */
    val seriesId: String,

    /**
     * The timestamp of the timeserial.
     */
    val timestamp: Long,

    /**
     * The counter of the timeserial.
     */
    val counter: Int,

    /**
     * The index of the timeserial.
     */
    val index: Int?,
) : Comparable<Timeserial> {
    @Suppress("ReturnCount")
    override fun compareTo(other: Timeserial): Int {
        val timestampDiff = timestamp.compareTo(other.timestamp)
        if (timestampDiff != 0) return timestampDiff

        // Compare the counter
        val counterDiff = counter.compareTo(other.counter)
        if (counterDiff != 0) return counterDiff

        // Compare the seriesId lexicographically
        val seriesIdDiff = seriesId.compareTo(other.seriesId)
        if (seriesIdDiff != 0) return seriesIdDiff

        // Compare the index, if present
        return if (index != null && other.index != null) index.compareTo(other.index) else 0
    }

    companion object {
        @Suppress("DestructuringDeclarationWithTooManyEntries")
        fun parse(timeserial: String): Timeserial {
            val matched = """(\w+)@(\d+)-(\d+)(?::(\d+))?""".toRegex().matchEntire(timeserial)
                ?: throw AblyException.fromErrorInfo(
                    ErrorInfo("invalid timeserial", HttpStatusCodes.InternalServerError, ErrorCodes.InternalError),
                )

            val (seriesId, timestamp, counter, index) = matched.destructured

            return Timeserial(
                seriesId = seriesId,
                timestamp = timestamp.toLong(),
                counter = counter.toInt(),
                index = if (index.isNotBlank()) index.toInt() else null,
            )
        }
    }
}
