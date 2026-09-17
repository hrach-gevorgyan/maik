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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.R
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
    waitingHint: String = "",
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sendLabel = stringResource(R.string.chat_send)
    val messageBox = stringResource(R.string.chat_message_box)
    val stopLabel = stringResource(R.string.chat_stop)
    // Typing is always allowed, so a question can be ready by the time the model is.
    val canSend = value.isNotBlank() && !busy && ready

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
                        busy -> stringResource(R.string.chat_maik_is_answering)
                        !ready -> waitingHint.ifEmpty { stringResource(R.string.chat_waiting_for_the_model) }
                        else -> stringResource(R.string.chat_ask_maik_anything)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
            // Text set from outside (a starter) puts the cursor at its end, ready to type on.
            var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    onValueChange(it.text)
                },
                enabled = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { contentDescription = messageBox }
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
                .semantics { contentDescription = if (busy) stopLabel else sendLabel }
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
