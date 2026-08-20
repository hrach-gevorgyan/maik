package com.maik.app

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

class TokenEstimateTest {

    @Test
    fun `estimate grows with length and never returns zero`() {
        assertTrue(ChatViewModel.estimateTokens("") >= 1)
        val short = ChatViewModel.estimateTokens("hello")
        val long = ChatViewModel.estimateTokens("hello ".repeat(100))
        assertTrue(long > short)
    }

    @Test
    fun `estimate is pessimistic enough to under-fill the window`() {
        // Real tokenizers average ~4 chars per token for English; we assume 3.2,
        // so our count must come out higher than the realistic one.
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val realistic = text.length / 4
        assertTrue(ChatViewModel.estimateTokens(text) > realistic)
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

class ModelCatalogTest {

    @Test
    fun `every model is ungated, https and a known bundle format`() {
        Models.ALL.forEach { model ->
            assertTrue(model.url, model.url.startsWith("https://"))
            assertTrue(
                model.url,
                model.url.endsWith(".litertlm") || model.url.endsWith(".task")
            )
        }
    }

    @Test
    fun `no GPU-only bundles ship, since they cannot fall back to CPU`() {
        // A -gpu bundle fails to load on the CPU executor, which is exactly the
        // path taken when the GPU delegate is refused.
        Models.ALL.forEach { model ->
            assertTrue(model.url, !model.url.contains("-gpu.") && !model.url.contains("_gpu."))
        }
    }

    @Test
    fun `ids are unique and the filename keeps the bundle extension`() {
        assertEquals(Models.ALL.size, Models.ALL.map { it.id }.toSet().size)
        Models.ALL.forEach { model ->
            assertTrue(model.fileName, model.fileName.endsWith(model.url.substringAfterLast('.')))
        }
    }

    @Test
    fun `an unknown or missing id falls back to the default`() {
        assertEquals(Models.DEFAULT, Models.byId(null))
        assertEquals(Models.DEFAULT, Models.byId("something-that-was-removed"))
        assertEquals(Models.GEMMA4_E2B, Models.byId(Models.GEMMA4_E2B.id))
    }

    @Test
    fun `the default is the smallest download, so first run is cheapest`() {
        assertEquals(Models.ALL.minByOrNull { it.approxBytes }, Models.DEFAULT)
    }
}
