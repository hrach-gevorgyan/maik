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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.R
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

/* ================= conversation list ================= */

@Composable
fun ConversationListScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    val context = androidx.compose.ui.platform.LocalContext.current
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
            MaikIconButton(description = stringResource(R.string.list_settings), onClick = vm::openSettings) { Sliders(scheme.onBackground) }
        }
        HorizontalLine()

        // Search only earns its space once there is enough to search through.
        if (vm.conversations.size >= 2 || vm.query.isNotBlank()) {
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
                        stringResource(R.string.list_nothing_matches, vm.query.trim()),
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
                                    },
                                    writing = vm.generatingId == convo.id,
                                    onPin = { vm.togglePin(convo.id) },
                                    onRename = { renaming = convo },
                                    onDelete = { confirmDelete = convo }
                                )
                            }
                            HorizontalLine()
                        }
                    }
                }
            }

            AppearsIn {
                NewChatButton {
                    buzz()
                    vm.newChat()
                }
            }
        }
    }

    menuFor?.let { convo ->
        ActionSheet(
            title = convo.title,
            subtitle = pluralStringResource(R.plurals.list_message_count, convo.messages.size, convo.messages.size, relativeTime(convo.updatedAt)),
            actions = listOf(
                SheetAction(if (convo.pinned) stringResource(R.string.list_unpin) else stringResource(R.string.list_pin_to_top)) {
                    vm.togglePin(convo.id)
                    menuFor = null
                },
                SheetAction(stringResource(R.string.list_share_chat)) {
                    shareText(context, vm.chatAsText(convo.id))
                    menuFor = null
                },
                SheetAction(stringResource(R.string.list_rename)) {
                    renaming = convo
                    menuFor = null
                },
                SheetAction(stringResource(R.string.list_delete), destructive = true) {
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
            title = { DialogTitle(stringResource(R.string.list_delete_this_chat)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.list_delete_detail, convo.messages.size, convo.title, convo.messages.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(convo.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.list_delete), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.list_cancel), color = scheme.onSurfaceVariant)
                }
            }
        )
    }

    renaming?.let { convo ->
        var draft by rememberSaveable(convo.id, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(convo.title, selection = TextRange(0, convo.title.length)))
        }
        val focus = remember { FocusRequester() }
        fun save() {
            if (draft.text.isBlank()) return
            vm.rename(convo.id, draft.text)
            renaming = null
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle(stringResource(R.string.list_rename)) },
            text = {
                RenameField(
                    value = draft,
                    onValueChange = { draft = it },
                    onDone = ::save,
                    modifier = Modifier.focusRequester(focus)
                )
                // The dialog has its own window; wait for its first frame before focusing.
                LaunchedEffect(convo.id) {
                    withFrameNanos { }
                    runCatching { focus.requestFocus() }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.text.isNotBlank(), onClick = ::save) {
                    Text(stringResource(R.string.list_save), color = scheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.list_cancel), color = scheme.onSurfaceVariant)
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
                stringResource(R.string.list_new_chat),
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
                    stringResource(R.string.list_no_conversations_yet_everything_you),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    convo: Conversation,
    writing: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val pinned = stringResource(R.string.list_pinned)
    val pinLabel = stringResource(if (convo.pinned) R.string.list_action_unpin else R.string.list_action_pin)
    val renameLabel = stringResource(R.string.list_action_rename)
    val deleteLabel = stringResource(R.string.list_action_delete)
    val optionsLabel = stringResource(R.string.common_options)
    Row(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete panel behind it only shows where the row has moved.
            .background(scheme.background)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress, onLongClickLabel = optionsLabel)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(pinLabel) { onPin(); true },
                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true }
                )
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (convo.pinned) {
            Text(
                stringResource(R.string.list_pin),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .semantics { contentDescription = pinned }
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
            if (writing) stringResource(R.string.chat_writing) else relativeTime(convo.updatedAt),
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
                    stringResource(R.string.list_delete),
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
    val focusManager = LocalFocusManager.current
    val label = stringResource(R.string.list_search_label)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.list_search_conversations),
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label }
            )
        }
        if (value.isNotEmpty()) {
            val clear = stringResource(R.string.list_clear_search)
            Text(
                "×",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = clear) {
                        onValueChange("")
                        focusManager.clearFocus()
                    }
                    .semantics { contentDescription = clear }
            )
        }
    }
}

/** A single-line field for renaming, with the text selected and Enter to save. */
@Composable
private fun RenameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.list_rename)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.background)
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
        )
    }
}
