package com.maik.app.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/* ================= conversation list ================= */

@Composable
fun ConversationListScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Wordmark()
            Spacer(Modifier.width(12.dp))
            OnDevicePill()
            Spacer(Modifier.weight(1f))
            MaikIconButton(description = "Settings", onClick = vm::openSettings) { Sliders(scheme.onBackground) }
        }
        HorizontalLine()

        // Search only earns its space once there is enough to search through.
        if (vm.conversations.size >= 5) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SearchField(vm.query) { vm.query = it }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                vm.conversations.isEmpty() -> EmptyList()

                vm.visibleConversations.isEmpty() -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Nothing matches \"${vm.query.trim()}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    itemsIndexed(
                        vm.visibleConversations,
                        key = { _, convo -> convo.id }
                    ) { _, convo ->
                        Column(Modifier.animateItem()) {
                            SwipeToDelete(onDelete = { confirmDelete = convo }) {
                                ConversationRow(
                                    convo = convo,
                                    onOpen = { vm.open(convo.id) },
                                    onLongPress = {
                                        buzz()
                                        menuFor = convo
                                    }
                                )
                            }
                            HorizontalLine()
                        }
                    }
                }
            }

            NewChatButton {
                buzz()
                vm.newChat()
            }
        }
    }

    menuFor?.let { convo ->
        ActionSheet(
            title = convo.title,
            subtitle = "${convo.messages.size} messages · ${relativeTime(convo.updatedAt)}",
            actions = listOf(
                SheetAction(if (convo.pinned) "Unpin" else "Pin to top") {
                    vm.togglePin(convo.id)
                    menuFor = null
                },
                SheetAction("Rename") {
                    renaming = convo
                    menuFor = null
                },
                SheetAction("Delete", destructive = true) {
                    confirmDelete = convo
                    menuFor = null
                }
            ),
            onDismiss = { menuFor = null }
        )
    }

    confirmDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Delete this chat?") },
            text = {
                Text(
                    "\"${convo.title}\" and its ${convo.messages.size} messages will be gone for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(convo.id)
                    confirmDelete = null
                }) { Text("Delete", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel", color = scheme.onSurfaceVariant)
                }
            }
        )
    }

    renaming?.let { convo ->
        var draft by remember(convo.id) { mutableStateOf(convo.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Rename") },
            text = { EditorField(draft, singleLine = true) { draft = it } },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(convo.id, draft)
                    renaming = null
                }) { Text("Save", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("Cancel", color = scheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier
                .padding(20.dp)
                .pressable(source)
                .clip(CircleShape)
                .background(scheme.primary)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Plus(scheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text(
                "New chat",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimary
            )
        }
    }
}

@Composable
private fun EmptyList() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        RisesIn(key = "empty") {
            Column {
                Wordmark(size = 56)
                Spacer(Modifier.height(14.dp))
                Text(
                    "No conversations yet.\nEverything you start stays on this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete panel behind it only shows where the row has moved.
            .background(scheme.background)
            .combinedClick(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (convo.pinned) {
            Text(
                "PIN",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .semantics { contentDescription = "Pinned" }
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                convo.title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (convo.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    convo.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            relativeTime(convo.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
        )
    }
}

/**
 * Swipe a chat aside to delete it. The row itself confirms first, so a swipe that
 * was meant as a scroll can never lose a conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                buzz()
                onDelete()
            }
            // Never actually dismiss: the confirmation decides, and the row springs back.
            false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scheme.error.copy(alpha = 0.12f))
                    .padding(horizontal = 24.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.error
                )
            }
        },
        content = { content() }
    )
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                "Search conversations",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
