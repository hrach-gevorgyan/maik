package com.maik.app

import com.maik.app.data.Message
import com.maik.app.engine.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun turns(count: Int, length: Int = 40) = (0 until count).map { i ->
        Message("x".repeat(length), fromUser = i % 2 == 0)
    }

    @Test
    fun `a short chat is replayed whole`() {
        val history = turns(4)
        assertEquals(history, ContextBudget.recentTurns(history, contextTokens = 2048, systemPrompt = "Be brief."))
    }

    @Test
    fun `a long chat keeps only the newest turns`() {
        val history = turns(200, length = 300)
        val kept = ContextBudget.recentTurns(history, contextTokens = 2048, systemPrompt = "Be brief.")
        assertTrue("nothing was kept", kept.isNotEmpty())
        assertTrue("everything was kept", kept.size < history.size)
        assertEquals("the newest turn must survive", history.last(), kept.last())
    }

    @Test
    fun `replayed history stays within its share of the window`() {
        val history = turns(200, length = 300)
        val kept = ContextBudget.recentTurns(history, contextTokens = 2048, systemPrompt = "")
        val cost = kept.sumOf { ContextBudget.estimateTokens(it.text) + 8 }
        assertTrue("seeded $cost tokens", cost <= (2048 * ContextBudget.SEED_SHARE).toInt())
    }

    @Test
    fun `a rebuilt conversation never opens on the model's turn`() {
        // The runtime rejects a conversation whose first turn is the model's.
        val history = turns(200, length = 300)
        val kept = ContextBudget.recentTurns(history, contextTokens = 2048, systemPrompt = "")
        assertTrue(kept.first().fromUser)
    }

    @Test
    fun `a huge system prompt leaves no room rather than going negative`() {
        val kept = ContextBudget.recentTurns(turns(10), contextTokens = 2048, systemPrompt = "y".repeat(10_000))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `full only past the threshold`() {
        assertFalse(ContextBudget.isFull(usedTokens = 1000, contextTokens = 2048))
        assertTrue(ContextBudget.isFull(usedTokens = 1700, contextTokens = 2048))
    }

    @Test
    fun `estimate grows with length and never returns zero`() {
        assertTrue(ContextBudget.estimateTokens("") >= 1)
        assertTrue(ContextBudget.estimateTokens("hello ".repeat(100)) > ContextBudget.estimateTokens("hello"))
    }

    @Test
    fun `estimate is pessimistic enough to under-fill the window`() {
        // Real tokenizers average about 4 characters per token for English.
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        assertTrue(ContextBudget.estimateTokens(text) > text.length / 4)
    }
}
