package com.maik.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Just enough Markdown for what a small model actually emits: fenced code,
 * inline code, bold, italic, headings, bullet and numbered lists.
 *
 * A full CommonMark parser would be a dependency and a lot of surface area for
 * text that is usually three paragraphs long. Anything unrecognised falls through
 * as plain text, which is the right failure for chat.
 */
sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val text: String, val level: Int) : Block
    data class Bullet(val text: String, val ordinal: String?) : Block
    data class Code(val text: String, val language: String?) : Block
}

fun parseMarkdown(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = source.lines()
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += Block.Paragraph(text)
        paragraph.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flush()
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += Block.Code(code.toString().trimEnd('\n'), language)
            }

            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                blocks += Block.Heading(trimmed.drop(level).trim(), level)
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                blocks += Block.Bullet(trimmed.drop(2).trim(), null)
            }

            trimmed.matches(ORDERED) -> {
                flush()
                val ordinal = trimmed.takeWhile { it.isDigit() }
                blocks += Block.Bullet(trimmed.dropWhile { it.isDigit() }.drop(2).trim(), ordinal)
            }

            trimmed.isEmpty() -> flush()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trimEnd())
            }
        }
        i++
    }
    flush()
    return blocks
}

private val ORDERED = Regex("^\\d+[.)] .*")

/** `**bold**`, `*italic*`, `_italic_` and `` `code` `` inside a run of text. */
fun inlineMarkdown(source: String, codeColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < source.length) {
        val rest = source.substring(i)
        val bold = rest.startsWith("**")
        val code = rest.startsWith("`")
        val italic = !bold && (rest.startsWith("*") || rest.startsWith("_"))

        val marker = when {
            bold -> "**"
            code -> "`"
            italic -> rest.take(1)
            else -> null
        }

        if (marker == null) {
            append(source[i])
            i++
            continue
        }

        val close = source.indexOf(marker, i + marker.length)
        if (close < 0) {
            // An unmatched marker is far more likely to be punctuation than markup.
            append(source[i])
            i++
            continue
        }

        val inner = source.substring(i + marker.length, close)
        when {
            bold -> withStyle(SpanStyle(fontWeight = FontWeight(700))) { append(inner) }
            italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inner) }
            code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = codeColor
                )
            ) { append(inner) }
        }
        i = close + marker.length
    }
}

@Composable
fun MarkdownText(source: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseMarkdown(source) }
    val scheme = MaterialTheme.colorScheme
    val codeColor = scheme.primary

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(if (block is Block.Bullet) 4.dp else 10.dp))
            when (block) {
                is Block.Paragraph -> Text(
                    inlineMarkdown(block.text, codeColor),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color
                )

                is Block.Heading -> Text(
                    inlineMarkdown(block.text, codeColor),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight(700),
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            else -> 16.sp
                        }
                    ),
                    color = color
                )

                is Block.Bullet -> Row {
                    Text(
                        block.ordinal?.let { "$it." } ?: "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = color.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineMarkdown(block.text, codeColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color
                    )
                }

                is Block.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.background.copy(alpha = 0.45f))
                        .padding(PaddingValues(12.dp))
                ) {
                    block.language?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = color.copy(alpha = 0.35f)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    // Code must never force the bubble wider than the screen.
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        color = color.copy(alpha = 0.9f),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
