package com.maik.app.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.util.AtomicFile
import com.maik.app.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
@Serializable
data class Message(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val at: Long = System.currentTimeMillis(),
    /** Speed figures for a reply, shown only in debug mode. */
    val stats: String? = null
)

@Immutable
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Which model this chat is held with. Null means "whatever is currently
     * selected" — set on the first reply so a conversation keeps one voice even
     * after you switch models elsewhere.
     */
    val modelId: String? = null,
    /** Pinned chats stay at the top of the list, whatever was used last. */
    val pinned: Boolean = false
) {
    val preview: String
        get() = messages.lastOrNull()?.text?.replace('\n', ' ')?.take(90).orEmpty()

    companion object {
        /** Titles come from the first thing the user said — no model call needed. */
        fun titleFrom(text: String): String {
            val cleaned = text.trim().replace(Regex("\\s+"), " ")
            return if (cleaned.length <= 34) cleaned else cleaned.take(33).trimEnd() + "…"
        }
    }
}

/**
 * Whole-file JSON persistence. A chat history is a few hundred KB at worst, so a
 * database would be ceremony.
 *
 * Writes go through [AtomicFile]: a crash mid-save leaves the previous file intact
 * instead of a half-written one that would wipe every chat on the next launch.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "conversations.json")
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<Conversation> {
        // No early exists() check: after a crash mid-save only the backup may be on
        // disk, and reading through AtomicFile is what restores it.
        return try {
            json.decodeFromString<List<Conversation>>(String(atomic.readFully(), Charsets.UTF_8))
                .sortedByDescending { it.updatedAt }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (_: Exception) {
            // Set the unreadable file aside rather than overwrite it with an empty list
            // on the next save: the chats may still be recoverable.
            runCatching { file.renameTo(File(file.parentFile, "conversations.corrupt-${System.currentTimeMillis()}.json")) }
            emptyList()
        }
    }

    fun save(conversations: List<Conversation>) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.encodeToString(conversations).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { atomic.failWrite(it) }
        }
    }
}

/** "now", "14m", "3h", "2d", "12 Mar" — compact enough for a list row. */
fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - at).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> android.text.format.DateFormat.format("d MMM", at).toString()
    }
}
