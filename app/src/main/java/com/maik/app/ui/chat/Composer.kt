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
    onVoice: (() -> Unit)? = null,
    photo: String? = null,
    onAttach: (() -> Unit)? = null,
    onRemovePhoto: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sendLabel = stringResource(R.string.chat_send)
    val messageBox = stringResource(R.string.chat_message_box)
    val stopLabel = stringResource(R.string.chat_stop)
    // Typing is always allowed, so a question can be ready by the time the model is.
    val canSend = (value.isNotBlank() || photo != null) && !busy && ready

    // The chosen photo waits above the box until it is sent or removed.
    androidx.compose.animation.AnimatedVisibility(visible = photo != null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            photo?.let { PhotoThumb(it, size = 72.dp) }
            MaikIconButton(description = stringResource(R.string.photo_remove), onClick = onRemovePhoto) {
                Text("×", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onAttach != null) {
            Box(Modifier.padding(bottom = 2.dp)) {
                MaikIconButton(description = stringResource(R.string.photo_add), onClick = onAttach) {
                    CameraGlyph(scheme.onSurfaceVariant)
                }
            }
        }
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

        val listening = onVoice != null && !busy && value.isBlank() && photo == null
        val voiceLabel = stringResource(R.string.voice_speak)
        val source = rememberPressSource()
        val bg by animateColorAsState(
            if (canSend || busy || listening) scheme.primary else scheme.surfaceVariant,
            tween(Motion.NORMAL),
            label = "sendBg"
        )
        Box(
            Modifier
                .size(52.dp)
                .pressable(source)
                .clip(CircleShape)
                .background(bg)
                .semantics {
                    contentDescription = when {
                        busy -> stopLabel
                        listening -> voiceLabel
                        else -> sendLabel
                    }
                }
                .clickable(
                    enabled = canSend || busy || listening,
                    interactionSource = source,
                    indication = null,
                    onClick = when {
                        busy -> onStop
                        listening -> onVoice!!
                        else -> onSend
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = when {
                    busy -> 0
                    listening -> 1
                    else -> 2
                },
                transitionSpec = { scaleIn(tween(Motion.QUICK)) togetherWith scaleOut(tween(Motion.QUICK)) },
                label = "sendGlyph"
            ) { mode ->
                when (mode) {
                    0 -> StopSquare(scheme.onPrimary)
                    1 -> Microphone(scheme.onPrimary)
                    else -> ArrowUp(
                        if (canSend) scheme.onPrimary
                        else scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
