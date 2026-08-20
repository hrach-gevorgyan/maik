package com.maik.app

/**
 * Trims a raw generation down to the part meant for the reader.
 *
 * Small models do not reliably stop at their own end-of-turn token. They emit it as
 * ordinary text and carry on, inventing both halves of a conversation until the
 * token budget runs out — which is what produced the rambling, debris-laden replies
 * in 1.4.x. The runtime does not cut this for us, so the app has to.
 *
 * Every marker below is a real control token from a shipping model's template:
 * ChatML for SmolLM, `<|user|>`/`<|end|>` for Phi-4-mini, `</s>` for TinyLlama, and
 * the full-width bar forms for DeepSeek-R1.
 */
object Reply {

    /** Anything from here on belongs to a turn nobody asked for. */
    private val TURN_MARKERS = listOf(
        "<|im_end|>",
        "<|im_start|>",
        "<|end|>",
        "<|endoftext|>",
        "<|user|>",
        "<|assistant|>",
        "<|system|>",
        "</s>",
        "<s>",
        "<end_of_turn>",
        "<start_of_turn>",
        "<｜end▁of▁sentence｜>",
        "<｜User｜>",
        "<｜Assistant｜>"
    )

    /**
     * Byte-level BPE artefacts, mapped back to what they stand for rather than
     * deleted: 'Ġ' is how such a tokenizer writes a space, 'Ċ' a newline. Removing
     * them outright would weld neighbouring words together.
     */
    private val DEBRIS = mapOf(
        "Ġ" to " ",
        "Ċ" to "\n",
        "Ā" to ""
    )

    fun clean(raw: String): String {
        var text = raw

        // Cut at the earliest turn marker; everything after it is invented.
        val cut = TURN_MARKERS.mapNotNull { marker ->
            text.indexOf(marker).takeIf { it >= 0 }
        }.minOrNull()
        if (cut != null) text = text.substring(0, cut)

        DEBRIS.forEach { (artefact, meaning) -> text = text.replace(artefact, meaning) }

        // A literal backslash-n is how these templates spell a newline; if one
        // survives at the tail it is the start of a cut-off marker, not content.
        text = text.trimEnd().removeSuffix("\\n").trimEnd()

        return text.trim()
    }

    /**
     * True once the stream has produced a turn marker, so generation can be
     * abandoned instead of burning the rest of the budget on invented dialogue.
     */
    fun isComplete(raw: String): Boolean = TURN_MARKERS.any { it in raw }
}
