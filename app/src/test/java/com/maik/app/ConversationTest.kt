package com.maik.app

import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    @Test
    fun `short messages become the title unchanged`() {
        assertEquals("What is a monad?", Conversation.titleFrom("  What is a monad?  "))
    }

    @Test
    fun `long messages are elided, never longer than the cap`() {
        val title = Conversation.titleFrom("a".repeat(200))
        assertTrue(title.length <= 34)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `newlines and runs of spaces collapse`() {
        assertEquals("one two three", Conversation.titleFrom("one\n\ntwo    three"))
    }

    @Test
    fun `preview comes from the newest message, flattened`() {
        val convo = Conversation(
            id = "x",
            title = "t",
            messages = listOf(
                Message("first", fromUser = true),
                Message("line one\nline two", fromUser = false)
            )
        )
        assertEquals("line one line two", convo.preview)
    }

    @Test
    fun `an empty conversation has no preview`() {
        assertEquals("", Conversation(id = "x", title = "t").preview)
    }
}


class RelativeTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `just-now and minutes`() {
        assertEquals("now", relativeTime(now - 5_000, now))
        assertEquals("14m", relativeTime(now - 14 * 60_000, now))
    }

    @Test
    fun `hours and days`() {
        assertEquals("3h", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d", relativeTime(now - 2 * 86_400_000, now))
    }

    @Test
    fun `a clock skewed into the future reads as now, not a negative`() {
        assertEquals("now", relativeTime(now + 60_000, now))
    }
}
