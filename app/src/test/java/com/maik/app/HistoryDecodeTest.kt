package com.maik.app

import com.maik.app.data.decodeConversations
import com.maik.app.data.historyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Chat history written by older or newer versions must still open. */
class HistoryDecodeTest {

    @Test
    fun `a file from before pinning existed still reads, unpinned`() {
        val old = """[{"id":"a","title":"Old chat","messages":[{"text":"hi","fromUser":true,"at":1}]}]"""
        val chats = decodeConversations(historyJson, old)!!
        assertEquals("Old chat", chats.single().title)
        assertFalse(chats.single().pinned)
    }

    @Test
    fun `fields added by a newer version are ignored`() {
        val newer = """[{"id":"a","title":"Chat","folder":"work","messages":[]}]"""
        assertEquals("Chat", decodeConversations(historyJson, newer)!!.single().title)
    }

    @Test
    fun `one broken conversation does not take the others with it`() {
        val mixed = """[{"id":"a","title":"Fine"},{"id":7,"title":{"oops":true}},{"id":"c","title":"Also fine"}]"""
        assertEquals(listOf("Fine", "Also fine"), decodeConversations(historyJson, mixed)!!.map { it.title })
    }

    @Test
    fun `a file that isn't a list at all gives nothing to salvage`() {
        assertNull(decodeConversations(historyJson, "this is not json"))
    }
}
