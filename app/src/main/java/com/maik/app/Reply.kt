package com.maik.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Trims a raw generation down to the part meant for the reader.
 *
 * Two things go wrong with these models, both observed on a device:
 *
 * 1. They do not stop. A model answers, emits its own end-of-turn token as ordinary
 *    text, and carries on inventing both halves of a conversation until the budget
 *    runs out. The runtime does not cut this, so the app does.
 *
 * 2. Byte-level tokenizer encoding leaks into the decoded text. An emoji arrives as
 *    a run of Latin characters — `ðŁĨ` is the bytes `F0 9F 86`, a 4-byte emoji cut
 *    one byte short.
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
     * The GPT-2 byte encoder: every raw byte mapped to one printable character.
     * Bytes 33–126, 161–172 and 174–255 stand for themselves, so ASCII passes
     * through untouched — which is what makes reversing it safe on ordinary text.
     */
    private val byteOf: Map<Char, Int> by lazy {
        val direct = ((33..126) + (161..172) + (174..255)).toSet()
        val map = HashMap<Char, Int>(256)
        direct.forEach { map[it.toChar()] = it }
        var spare = 0
        for (b in 0..255) {
            if (b !in direct) {
                map[(256 + spare).toChar()] = b
                spare++
            }
        }
        map
    }

    fun clean(raw: String): String {
        var text = raw

        // Cut at the earliest turn marker; everything after it is invented.
        val cut = TURN_MARKERS.mapNotNull { marker ->
            text.indexOf(marker).takeIf { it >= 0 }
        }.minOrNull()
        if (cut != null) text = text.substring(0, cut)

        text = repairBytes(text)

        // A literal backslash-n is how these templates spell a newline; one left at
        // the tail is the start of a marker we cut, not content.
        text = text.trimEnd().removeSuffix("\\n").trimEnd()

        return text.trim()
    }

    /**
     * Rebuilds text that arrived in byte-level encoding.
     *
     * Only runs of non-ASCII characters are touched, and only when they decode to
     * valid UTF-8 — so genuinely accented text is left alone, and a truncated emoji
     * is dropped rather than shown as gibberish.
     */
    fun repairBytes(text: String): String {
        if (text.all { it.code < 128 }) return text

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.code < 128 || !byteOf.containsKey(ch)) {
                out.append(ch)
                i++
                continue
            }

            var end = i
            while (end < text.length && text[end].code >= 128 && byteOf.containsKey(text[end])) {
                end++
            }

            val bytes = ByteArray(end - i) { byteOf.getValue(text[i + it]).toByte() }
            decodeUtf8(bytes)?.let(out::append)
            // A run that is not valid UTF-8 is a truncated character. Drop it: half
            // an emoji is noise, and showing it as Latin letters is worse.
            i = end
        }
        return out.toString()
    }

    /** Strict UTF-8 decode, or null when the bytes are not a complete sequence. */
    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    /**
     * True once the stream has produced a turn marker, so generation can be
     * abandoned instead of burning the rest of the budget on invented dialogue.
     */
    fun isComplete(raw: String): Boolean = TURN_MARKERS.any { it in raw }
}
