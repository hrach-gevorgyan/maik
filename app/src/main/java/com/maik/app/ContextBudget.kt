package com.maik.app

/**
 * How much chat history a conversation is seeded with, and when it is full.
 *
 * Pure arithmetic, kept apart from the view model so it can be tested: getting it
 * wrong either forgets the chat or makes the model re-read far more than it needs.
 */
object ContextBudget {

    /** Share of the window given to replayed history; the rest is for the reply and growth. */
    const val SEED_SHARE = 0.3

    /** Past this share of the window, the conversation is rebuilt from recent turns. */
    const val FULL_SHARE = 0.8

    /** Role markers and separators the runtime adds around each turn. */
    private const val PER_TURN_OVERHEAD = 8

    /** Rough for English, deliberately pessimistic so the history under-fills. */
    fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1

    /**
     * The newest turns that fit in [SEED_SHARE] of the window after the system prompt.
     * Never starts on the model's turn, since a conversation cannot open that way.
     */
    fun recentTurns(history: List<Message>, contextTokens: Int, systemPrompt: String): List<Message> {
        var budget = (contextTokens * SEED_SHARE).toInt() - estimateTokens(systemPrompt)
        val kept = ArrayDeque<Message>()
        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text) + PER_TURN_OVERHEAD
            if (budget - cost < 0) break
            budget -= cost
            kept.addFirst(message)
        }
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
        return kept.toList()
    }

    fun isFull(usedTokens: Int, contextTokens: Int): Boolean =
        usedTokens > (contextTokens * FULL_SHARE).toInt()
}
