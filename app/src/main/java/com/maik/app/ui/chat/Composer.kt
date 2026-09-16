package com.maik.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

@Composable
internal fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    ready: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val enabled = !busy && ready
    val canSend = value.isNotBlank() && enabled

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    when {
                        busy -> "maik is answering…"
                        !ready -> "Waiting for the model…"
                        else -> "Ask maik anything…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val source = rememberPressSource()
        val bg by animateColorAsState(
            if (canSend || busy) scheme.primary else scheme.surfaceVariant,
            tween(Motion.NORMAL),
            label = "sendBg"
        )
        Box(
            Modifier
                .size(52.dp)
                .pressable(source)
                .clip(CircleShape)
                .background(bg)
                .semantics { contentDescription = if (busy) "Stop" else "Send" }
                .clickable(
                    enabled = canSend || busy,
                    interactionSource = source,
                    indication = null,
                    onClick = if (busy) onStop else onSend
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = busy,
                transitionSpec = { scaleIn(tween(Motion.QUICK)) togetherWith scaleOut(tween(Motion.QUICK)) },
                label = "sendGlyph"
            ) { running ->
                if (running) {
                    StopSquare(scheme.onPrimary)
                } else {
                    ArrowUp(
                        if (canSend) scheme.onPrimary
                        else scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
