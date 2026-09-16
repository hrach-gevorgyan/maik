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
    companion object {
        /**
         * @param have bytes already in the partial file
         * @param responseCode the server's reply to a request that asked for `bytes=have-`
         * @param contentLength what the server says this response carries, or <= 0 if unknown
         * @param expectedTotal the model's known size, used when the server doesn't say
         */
        fun of(have: Long, responseCode: Int, contentLength: Long, expectedTotal: Long): ResumePlan {
            // 206 means the server is continuing from where we stopped. A 200 means it
            // ignored the range and is sending the whole file, so the partial is worthless.
            val resuming = have > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            val start = if (resuming) have else 0L
            val remaining = contentLength.takeIf { it > 0 } ?: (expectedTotal - start)
            return ResumePlan(resuming, start, start + remaining)
        }
    }
}
