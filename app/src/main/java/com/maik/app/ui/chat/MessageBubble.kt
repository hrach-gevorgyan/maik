package com.maik.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.R
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(msg: Message, onLongPress: (() -> Unit)? = null, match: Match = Match.None) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    val optionsLabel = stringResource(R.string.chat_message_options)
    val ring = if (msg.fromUser) scheme.onBackground else scheme.primary

    val bg = when {
        msg.isError -> scheme.error.copy(alpha = 0.12f)
        msg.fromUser -> scheme.primary
        else -> scheme.surfaceVariant
    }
    val fg = when {
        msg.isError -> scheme.error
        msg.fromUser -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 330.dp)
                .animateContentSize(tween(Motion.QUICK, easing = LinearOutSlowInEasing))
                .clip(
                    if (msg.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                    else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                )
                .background(bg)
                .then(
                    when (match) {
                        Match.None -> Modifier
                        // Your bubbles are already the accent colour, so their outline is ink.
                        Match.Other -> Modifier.border(1.5.dp, ring.copy(alpha = 0.45f), bubbleShape(msg))
                        Match.Current -> Modifier.border(2.5.dp, ring, bubbleShape(msg))
                    }
                )
                // Long press only: a tap does nothing, so TalkBack must not offer one.
                .then(
                    if (onLongPress == null) Modifier
                    else Modifier
                        .pointerInput(onLongPress) {
                            detectTapGestures(onLongPress = {
                                buzz()
                                onLongPress()
                            })
                        }
                        .semantics {
                            onLongClick(label = optionsLabel) {
                                onLongPress()
                                true
                            }
                        }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                msg.imagePath?.let {
                    PhotoThumb(it, size = 200.dp)
                    Spacer(Modifier.height(8.dp))
                }
                // What the user typed is literal; only replies are marked up.
                if (msg.fromUser || msg.isError) {
                    Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = fg)
                } else {
                    MarkdownText(msg.text, fg)
                }
            }
        }
    }
}

/** How a bubble relates to the search in progress. */
enum class Match { None, Other, Current }

private fun bubbleShape(msg: Message) =
    if (msg.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

/** The full message, selectable word by word, for copying part of an answer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectableMessageSheet(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceVariant,
        contentColor = scheme.onSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.chat_press_and_hold_to_select),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.muted
            )
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
        }
    }
}

/** Hands the message to whatever the phone can share with. */
internal fun shareText(context: Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.chat_share))) }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("maik", text))
    // Android 13+ shows its own copy confirmation; a second one would be noise.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
    }
}
