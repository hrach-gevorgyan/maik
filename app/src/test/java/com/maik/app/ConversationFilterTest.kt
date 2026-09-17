package com.maik.app

import com.maik.app.data.Conversation
import com.maik.app.data.Message
import com.maik.app.data.filterConversations
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFilterTest {

    private val trip = Conversation("a", "Trip", listOf(Message("paris is nice", fromUser = true)))
    private val code = Conversation("b", "Kotlin help", pinned = true)
    private val food = Conversation("c", "Dinner")

    @Test
    fun `pinned chats come first and the rest keep their order`() {
        assertEquals(listOf("b", "a", "c"), filterConversations(listOf(trip, code, food), "").map { it.id })
    }

    @Test
    fun `search matches message text as well as titles, ignoring case and spaces`() {
        assertEquals(listOf("a"), filterConversations(listOf(trip, code, food), "  PARIS ").map { it.id })
        assertEquals(listOf("b"), filterConversations(listOf(trip, code, food), "kotlin").map { it.id })
    }

    @Test
    fun `no match is an empty list, not an error`() {
        assertEquals(emptyList<Conversation>(), filterConversations(listOf(trip, code, food), "zzz"))
    }
}
