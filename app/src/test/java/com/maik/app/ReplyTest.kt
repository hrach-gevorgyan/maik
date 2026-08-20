package com.maik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are taken from real output observed on a device, not invented.
 */
class ReplyTest {

    @Test
    fun `a clean reply is left alone`() {
        assertEquals("Paris.", Reply.clean("Paris."))
    }

    @Test
    fun `everything after the end-of-turn token is discarded`() {
        // Verbatim from an emulator run: the model answered, emitted its stop token
        // as plain text, then role-played both sides of a conversation forever.
        val raw = "Hello! How can I help you today?<|im_end|>\\n<|im_start|>assistant\\n" +
            "I'm looking for a new hobby. What's your favorite hobby?<|im_end|>"
        assertEquals("Hello! How can I help you today?", Reply.clean(raw))
    }

    @Test
    fun `each family's own markers are honoured`() {
        assertEquals("Blue.", Reply.clean("Blue.</s>\\n<|assistant|>\\nAnother turn"))
        assertEquals("Blue.", Reply.clean("Blue.<|end|><|assistant|>more"))
        assertEquals("Blue.", Reply.clean("Blue.<｜end▁of▁sentence｜><｜User｜>more"))
        assertEquals("Blue.", Reply.clean("Blue.<end_of_turn>\\n<start_of_turn>model"))
    }

    @Test
    fun `tokeniser debris is stripped`() {
        assertEquals("Hello there", Reply.clean("HelloĠthere"))
        assertFalse(Reply.clean("done!ĠðŁ").contains("Ġ"))
    }

    @Test
    fun `a trailing literal backslash-n is not left dangling`() {
        // These templates spell newlines as a literal backslash-n, so a cut can
        // leave one stranded at the end.
        assertEquals("Paris.", Reply.clean("Paris.\\n"))
    }

    @Test
    fun `completion is detected as soon as a marker appears`() {
        assertFalse(Reply.isComplete("Paris is the capital"))
        assertTrue(Reply.isComplete("Paris.<|im_end|>"))
        assertTrue(Reply.isComplete("Paris.</s>"))
    }

    @Test
    fun `a reply that is nothing but a marker comes back empty`() {
        // The caller substitutes a placeholder; it must not show a raw token.
        assertEquals("", Reply.clean("<|im_end|>"))
    }

    @Test
    fun `an empty generation stays empty rather than throwing`() {
        assertEquals("", Reply.clean(""))
    }
}
