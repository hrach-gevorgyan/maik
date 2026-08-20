package com.maik.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Verbs the indicator cycles through. Deliberately vague: the model isn't really
 * doing distinct "steps", and pretending otherwise would be theatre.
 */
private val VERBS = listOf(
    "Thinking",
    "Working it through",
    "Weighing it up",
    "Following the thread",
    "Checking itself"
)

/**
 * The live indicator shown while a reasoning model is still inside its `<think>`
 * block: a shimmering verb, a running clock, and the reasoning itself scrolling
 * past underneath so it's visibly working rather than merely spinning.
 */
@Composable
fun ThinkingCard(reasoning: String, startedAt: Long) {
    val scheme = MaterialTheme.colorScheme
    var elapsed by remember(startedAt) { mutableIntStateOf(0) }
    var verb by remember(startedAt) { mutableIntStateOf(0) }

    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            // Slow enough to read, quick enough to feel alive.
            if (elapsed % 4 == 0) verb = (verb + 1) % VERBS.size
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot()
            Spacer(Modifier.width(10.dp))
            Shimmer {
                Text(
                    VERBS[verb],
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
            }
            Spacer(Modifier.weight(1f))
            if (elapsed > 0) {
                Text(
                    "${elapsed}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
        }

        if (reasoning.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            // Only the tail: this is a texture of work happening, not a document.
            Text(
                reasoning.takeLast(220),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.32f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The reasoning kept alongside a finished answer, collapsed until asked for. */
@Composable
fun ReasoningTrace(reasoning: String, seconds: Int) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (open) "Hide reasoning" else "Thought for ${seconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.width(6.dp))
            Chevron(open, scheme.onSurfaceVariant.copy(alpha = 0.4f))
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surface)
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            )
        }
    }
}

/* ---------- motion ---------- */

@Composable
private fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "dot")
    val scale by t.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scale"
    )
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/**
 * Sweeps a highlight across whatever it wraps. Drawn as a source-atop overlay so
 * it lights up the glyphs themselves rather than the box around them.
 */
@Composable
private fun Shimmer(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "x"
    )

    Box(
        Modifier.drawWithContent {
            drawContent()
            val width = size.width.coerceAtLeast(1f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        scheme.primary.copy(alpha = 0.85f),
                        Color.Transparent
                    ),
                    start = Offset(x * width - width * 0.4f, 0f),
                    end = Offset(x * width + width * 0.4f, size.height)
                ),
                blendMode = BlendMode.SrcAtop
            )
        }
    ) { content() }
}

@Composable
private fun Chevron(open: Boolean, tint: Color) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chev")
    Box(Modifier.graphicsLayer { rotationZ = rotation }) {
        androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
            val w = size.width
            drawLine(
                tint,
                Offset(w * 0.1f, w * 0.3f),
                Offset(w * 0.5f, w * 0.75f),
                w * 0.16f,
                androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                tint,
                Offset(w * 0.5f, w * 0.75f),
                Offset(w * 0.9f, w * 0.3f),
                w * 0.16f,
                androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}
