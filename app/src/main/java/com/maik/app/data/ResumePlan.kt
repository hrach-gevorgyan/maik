package com.maik.app.data

import com.maik.app.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
import java.net.HttpURLConnection

/**
 * Whether a download continues a partial file or starts again, and where it ends.
 *
 * Pure so it can be tested: getting it wrong either appends a whole second copy of a
 * 2.6 GB file onto a partial one, or throws away progress the server would have kept.
 */
data class ResumePlan(val resuming: Boolean, val start: Long, val total: Long) {

    /** What to do with the response to a request for `bytes=have-`. */
    sealed interface Outcome {
        data class Copy(val start: Long, val total: Long, val resuming: Boolean) : Outcome

        /** The partial file already holds the whole model. */
        data object AlreadyComplete : Outcome

        /** The partial can't be continued; delete it and ask for the whole file. */
        data object RestartFromZero : Outcome
    }

    companion object {
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

        /**
         * @param have bytes already in the partial file
         * @param responseCode the server's reply to a request that asked for `bytes=have-`
         * @param contentLength what the server says this response carries, or <= 0 if unknown
         * @param expectedTotal the model's known size, used when the server doesn't say
         */
        fun of(have: Long, responseCode: Int, contentLength: Long, expectedTotal: Long): ResumePlan {
            val resuming = have > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            val start = if (resuming) have else 0L
            val remaining = contentLength.takeIf { it > 0 } ?: (expectedTotal - start)
            return ResumePlan(resuming, start, start + remaining)
        }
    }
}
