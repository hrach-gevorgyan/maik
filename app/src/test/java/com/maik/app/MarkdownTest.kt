package com.maik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `consecutive lines form one paragraph`() {
        val blocks = parseMarkdown("one\ntwo\n\nthree")
        assertEquals(2, blocks.size)
        assertEquals("one\ntwo", (blocks[0] as Block.Paragraph).text)
        assertEquals("three", (blocks[1] as Block.Paragraph).text)
    }

    @Test
    fun `fenced code keeps its language and inner blank lines`() {
        val blocks = parseMarkdown("before\n```kotlin\nval a = 1\n\nval b = 2\n```\nafter")
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\n\nval b = 2", code.text)
        assertEquals(2, blocks.filterIsInstance<Block.Paragraph>().size)
    }

    @Test
    fun `fence without a language is still code`() {
        val code = parseMarkdown("```\nplain\n```").filterIsInstance<Block.Code>().single()
        assertEquals(null, code.language)
        assertEquals("plain", code.text)
    }

    @Test
    fun `headings carry their level, capped at three`() {
        val blocks = parseMarkdown("# One\n## Two\n##### Five")
        val levels = blocks.filterIsInstance<Block.Heading>().map { it.level }
        assertEquals(listOf(1, 2, 3), levels)
    }

    @Test
    fun `bullets and numbered items are both lists`() {
        val blocks = parseMarkdown("- first\n* second\n1. third")
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals(null, bullets[0].ordinal)
        assertEquals("1", bullets[2].ordinal)
        assertEquals("third", bullets[2].text)
    }

    @Test
    fun `inline markers style their content and drop the markers`() {
        val styled = inlineMarkdown("a **bold** and `code` here", androidx.compose.ui.graphics.Color.Red)
        assertEquals("a bold and code here", styled.text)
        assertTrue(styled.spanStyles.size >= 2)
    }

    @Test
    fun `an unmatched marker is left as literal text`() {
        // Multiplication and stray asterisks must not eat the rest of the message.
        val styled = inlineMarkdown("2 * 3 = 6", androidx.compose.ui.graphics.Color.Red)
        assertEquals("2 * 3 = 6", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `an unterminated code fence does not lose the rest of the message`() {
        val blocks = parseMarkdown("```\nstill streaming")
        assertEquals("still streaming", (blocks.single() as Block.Code).text)
    }
}
