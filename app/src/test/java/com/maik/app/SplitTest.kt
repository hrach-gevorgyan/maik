package com.maik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {

    @Test
    fun `plain output is all answer`() {
        val split = Split.of("Paris is the capital of France.")
        assertEquals("Paris is the capital of France.", split.answer)
        assertEquals("", split.reasoning)
        assertFalse(split.stillThinking)
    }

    @Test
    fun `closed think block separates reasoning from answer`() {
        val split = Split.of("<think>The user wants a capital.</think>Paris.")
        assertEquals("The user wants a capital.", split.reasoning)
        assertEquals("Paris.", split.answer)
        assertFalse(split.stillThinking)
    }

    @Test
    fun `open think block means still thinking`() {
        val split = Split.of("<think>Let me work through")
        assertEquals("Let me work through", split.reasoning)
        assertEquals("", split.answer)
        assertTrue(split.stillThinking)
    }

    @Test
    fun `closing tag without an opening one still yields an answer`() {
        // Some models emit the opening tag implicitly and only close it.
        val split = Split.of("weighing options</think>Berlin.")
        assertEquals("weighing options", split.reasoning)
        assertEquals("Berlin.", split.answer)
        assertFalse(split.stillThinking)
    }

    @Test
    fun `a lone angle bracket is not treated as markup`() {
        val split = Split.of("Use a < b to compare.")
        assertEquals("Use a < b to compare.", split.answer)
        assertFalse(split.stillThinking)
    }

    @Test
    fun `empty output is harmless`() {
        val split = Split.of("")
        assertEquals("", split.answer)
        assertFalse(split.stillThinking)
    }
}
