package com.maik.app.data

import java.net.HttpURLConnection

/**
 * Whether a download continues a partial file or starts again, and where it ends.
 *
 * Pure so it can be tested: getting it wrong either appends a whole second copy of a
 * 2.6 GB file onto a partial one, or throws away progress the server would have kept.
 */
object ResumePlan {

    /** What to do with the response to a request for `bytes=have-`. */
    sealed interface Outcome {
        data class Copy(val start: Long, val total: Long, val resuming: Boolean) : Outcome

        /** The partial file already holds the whole model. */
        data object AlreadyComplete : Outcome

        /** The partial can't be continued; delete it and ask for the whole file. */
        data object RestartFromZero : Outcome
    }

    private val CONTENT_RANGE = Regex("""^bytes\s+(\d+)-\d+/(\d+|\*)$""")

    /** The first byte of a `Content-Range: bytes a-b/N` header, or null. */
    fun parseContentRangeStart(header: String?): Long? =
        header?.trim()?.let { CONTENT_RANGE.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()

    /**
     * @param have bytes already in the partial file
     * @param code the server's HTTP status
     * @param contentLength what this response carries, or <= 0 if unknown
     * @param expected the model's known size
     * @param contentRangeStart where a 206 says its bytes begin
     */
    fun decide(have: Long, code: Int, contentLength: Long, expected: Long, contentRangeStart: Long?): Outcome {
        if (code == 416) {
            return if (have > 0 && have == expected) Outcome.AlreadyComplete else Outcome.RestartFromZero
        }
        if (have > expected) return Outcome.RestartFromZero
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            // A 206 that doesn't start where we stopped would splice two files together.
            if (contentRangeStart != have) return Outcome.RestartFromZero
            val length = contentLength.takeIf { it > 0 } ?: (expected - have)
            return Outcome.Copy(have, have + length, resuming = have > 0)
        }
        // A 200 ignores the range and sends the whole file.
        return Outcome.Copy(0, contentLength.takeIf { it > 0 } ?: expected, resuming = false)
    }
}
