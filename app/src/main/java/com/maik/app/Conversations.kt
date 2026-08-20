package com.maik.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Message(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val at: Long = System.currentTimeMillis()
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
 * database would be ceremony; the tradeoff is that every save rewrites the file.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "conversations.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<Conversation> = try {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<Conversation>>(file.readText())
            .sortedByDescending { it.updatedAt }
    } catch (_: Exception) {
        // A corrupt history should cost you your chats, not the whole app.
        emptyList()
    }

    fun save(conversations: List<Conversation>) {
        try {
            file.writeText(json.encodeToString(conversations))
        } catch (_: Exception) {
            // Nothing useful to do — the in-memory list is still intact.
        }
    }
}

/** "just now", "14m", "3h", "Tue", "12 Mar" — compact enough for a list row. */
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
