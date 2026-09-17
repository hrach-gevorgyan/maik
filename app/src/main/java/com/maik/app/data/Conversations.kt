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
import kotlinx.serialization.json.decodeFromJsonElement

@Immutable
@Serializable
data class Message(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val at: Long = System.currentTimeMillis(),
    /** Speed figures for a reply, shown only in debug mode. */
    val stats: String? = null,
    /** The reply stopped at the length limit rather than because it was finished. */
    val truncated: Boolean = false
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
/** How chat history is written and read: tolerant of fields added or removed later. */
val historyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

class ChatStore(context: Context) {

    private val file = File(context.filesDir, "conversations.json")
    private val atomic = AtomicFile(file)
    private val json = historyJson

    fun load(): List<Conversation> {
        // No early exists() check: after a crash mid-save only the backup may be on
        // disk, and reading through AtomicFile is what restores it.
        val text = try {
            String(atomic.readFully(), Charsets.UTF_8)
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        } catch (_: Exception) {
            return setAside()
        }
        val whole = runCatching { json.decodeFromString<List<Conversation>>(text) }.getOrNull()
        if (whole != null) return whole.sortedByDescending { it.updatedAt }
        // The file as a whole didn't read. Keep every conversation that still does, and a
        // copy of the original in case the rest can be recovered by hand.
        setAside()
        return decodeConversations(json, text).orEmpty().sortedByDescending { it.updatedAt }
    }

    /** Keeps one copy of an unreadable history, replacing any older one. */
    private fun setAside(): List<Conversation> {
        runCatching { file.copyTo(File(file.parentFile, "conversations.corrupt.json"), overwrite = true) }
        return emptyList()
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

/**
 * The chats the list shows: pinned ones first, each group keeping its newest-first
 * order, narrowed to those whose title or any message contains [query].
 */
fun filterConversations(all: List<Conversation>, query: String): List<Conversation> {
    val q = query.trim()
    val ordered = all.sortedByDescending { it.pinned }
    if (q.isEmpty()) return ordered
    return ordered.filter { convo ->
        convo.title.contains(q, ignoreCase = true) ||
            convo.messages.any { it.text.contains(q, ignoreCase = true) }
    }
}

/** Indices of the messages in [messages] that contain [query], oldest first. */
fun findInMessages(messages: List<Message>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}

/**
 * Reads a saved history, keeping every conversation that still parses even when others
 * in the same file don't. Returns null when the text isn't a list at all.
 */
fun decodeConversations(json: Json, text: String): List<Conversation>? =
    runCatching { json.decodeFromString<List<Conversation>>(text) }.getOrElse {
        runCatching {
            (json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<Conversation>(element) }.getOrNull()
            }
        }.getOrNull()
    }

/** How long ago something happened, in the unit a list row should show it in. */
sealed interface Ago {
    data object Now : Ago
    data class Minutes(val count: Long) : Ago
    data class Hours(val count: Long) : Ago
    data class Days(val count: Long) : Ago

    /** Over a week: shown as a date rather than a count. */
    data class On(val at: Long) : Ago
}

/** Pure, so it can be tested; the words for each case live in string resources. */
fun ago(at: Long, now: Long = System.currentTimeMillis()): Ago {
    val delta = (now - at).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> Ago.Now
        minutes < 60 -> Ago.Minutes(minutes)
        hours < 24 -> Ago.Hours(hours)
        days < 7 -> Ago.Days(days)
        else -> Ago.On(at)
    }
}
