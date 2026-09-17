package com.maik.app.ui.chat

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ================= chat ================= */

@Composable
internal fun ChatScreen(vm: ChatViewModel) {
    val convo = vm.current ?: return
    var input by rememberSaveable(convo.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val buzz = tap()
    val done = tick()
    // A soft tick when a reply lands, so you can look away while it writes.
    var wasBusy by remember { mutableStateOf(vm.busy) }
    LaunchedEffect(vm.busy) {
        if (wasBusy && !vm.busy) done()
        wasBusy = vm.busy
    }
    val count = convo.messages.size
    var pickingModel by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var selectingText by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    // The list is laid out from the bottom, so a growing reply pushes older messages
    // up by itself — no scrolling on every token, which is what made streaming judder.
    val atBottom by remember { derivedStateOf { !listState.canScrollBackward } }
    val scope = rememberCoroutineScope()

    // Your own message always brings you back down; a reply arriving doesn't yank you
    // away from something you scrolled up to read.
    val lastFromUser = convo.messages.lastOrNull()?.fromUser == true
    // Everything already in the chat when it opened is drawn as-is; only what arrives
    // after that animates, so opening a long chat isn't a wave of movement.
    val alreadyThere = rememberSaveable(convo.id) { count }
    LaunchedEffect(count) {
        if (count > 0 && (lastFromUser || listState.firstVisibleItemIndex <= 1)) {
            listState.animateScrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = convo.title,
            onBack = vm::openList,
            trailing = {
                // Tapping the model name swaps which one answers in this chat.
                Text(
                    vm.modelFor(convo).label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !vm.busy, onClickLabel = stringResource(R.string.chat_change_model)) { pickingModel = true }
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                )
            }
        )

        StatusStrip(vm, vm.modelFor(convo))

        Box(Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
        ) {
            // Items are listed newest first, because the list is drawn bottom-up.
            val last = convo.messages.lastOrNull()
            if (!vm.busy && last?.fromUser == false && vm.stage is Stage.Ready) {
                item(key = "again", contentType = 3) {
                    Box(Modifier.animateItem(fadeInSpec = tween(Motion.NORMAL, delayMillis = 120), placementSpec = null)) {
                        QuietAction(if (last.isError) stringResource(R.string.chat_try_again) else stringResource(R.string.chat_regenerate)) {
                            buzz()
                            vm.regenerate()
                        }
                    }
                }
            }

            // The live reply uses the key its saved message will have, so finishing a
            // reply is a quiet swap of the same row rather than one row vanishing and
            // another fading in.
            if (vm.busy) {
                item(key = "m$count", contentType = 1) {
                    Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null)) {
                        val text = vm.streaming
                        // The dots hand over to the first words instead of blinking out.
                        AnimatedContent(
                            targetState = text.isEmpty(),
                            transitionSpec = {
                                fadeIn(tween(Motion.NORMAL)) togetherWith fadeOut(tween(Motion.QUICK))
                            },
                            label = "live"
                        ) { waiting ->
                            if (waiting) TypingDots() else Bubble(Message(text, fromUser = false))
                        }
                    }
                }
            }

            for (index in convo.messages.indices.reversed()) {
                val msg = convo.messages[index]
                item(key = "m$index", contentType = if (msg.fromUser) 0 else 1) {
                    Column(Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)) {
                        val body = @Composable {
                            Column {
                                Bubble(msg, onLongPress = { menuFor = index })
                                if (vm.debugMode && msg.stats != null) SpeedLine(msg.stats)
                            }
                        }
                        if (index >= alreadyThere) AppearsIn { body() } else body()
                    }
                }
            }

            if (vm.stoppedForHeat) {
                item(key = "heat") {
                    ContextNotice(
                        stringResource(R.string.chat_maik_stopped_early_because_the)
                    )
                }
            }
            if (vm.dropped > 0) {
                item(key = "dropped") { ContextNotice(vm.dropped) }
            }
        }

        // An empty chat has nothing to anchor to the bottom, so it sits where the eye
        // lands instead of hugging the message box.
        if (convo.messages.isEmpty() && !vm.busy) {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                ChatEmptyState(vm.modelFor(convo).label, ready = vm.stage is Stage.Ready)
            }
        }

        // Scrolled up while a reply grows below: offer the way back rather than
        // pulling the reader down.
        androidx.compose.animation.AnimatedVisibility(
            visible = !atBottom && count > 0,
            enter = fadeIn(tween(Motion.NORMAL)) +
                slideInVertically(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { it / 2 } +
                scaleIn(tween(Motion.NORMAL), initialScale = 0.9f),
            exit = fadeOut(tween(Motion.QUICK)) + scaleOut(tween(Motion.QUICK), targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) {
            Text(
                stringResource(R.string.chat_jump_to_latest),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(role = Role.Button) {
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            )
        }
        }

        // Long press on any message: copy, select, share, edit, delete.
        menuFor?.let { index ->
            val msg = convo.messages.getOrNull(index)
            if (msg == null) menuFor = null else {
                val actions = buildList {
                    add(SheetAction(stringResource(R.string.chat_copy)) {
                        copyToClipboard(context, msg.text)
                        menuFor = null
                    })
                    add(SheetAction(stringResource(R.string.chat_select_text)) {
                        selectingText = msg.text
                        menuFor = null
                    })
                    add(SheetAction(stringResource(R.string.chat_share)) {
                        shareText(context, msg.text)
                        menuFor = null
                    })
                    if (msg.fromUser && !vm.busy) {
                        add(SheetAction(stringResource(R.string.chat_edit_and_send_again)) {
                            editing = index
                            menuFor = null
                        })
                    }
                    if (!vm.busy) {
                        add(SheetAction(stringResource(R.string.chat_delete_message), destructive = true) {
                            vm.deleteMessage(index, msg.at)
                            menuFor = null
                        })
                    }
                }
                ActionSheet(
                    title = if (msg.fromUser) stringResource(R.string.chat_your_message) else stringResource(R.string.chat_maik_s_reply),
                    subtitle = relativeTime(msg.at),
                    actions = actions,
                    onDismiss = { menuFor = null }
                )
            }
        }

        selectingText?.let { text ->
            SelectableMessageSheet(text) { selectingText = null }
        }

        editing?.let { index ->
            val editedMessage = convo.messages.getOrNull(index)
            val original = editedMessage?.text.orEmpty()
            EditMessageDialog(
                initial = original,
                onSend = {
                    vm.editAndResend(index, editedMessage?.at ?: -1L, it)
                    editing = null
                },
                onDismiss = { editing = null }
            )
        }

        if (pickingModel) {
            ModelPicker(
                current = vm.modelFor(convo),
                installed = vm.installedModels(),
                onPick = {
                    vm.setModelForCurrentChat(it)
                    pickingModel = false
                },
                onDismiss = { pickingModel = false }
            )
        }

        vm.pendingSwitch?.let { choice ->
            if (choice.chatId == convo.id) ModelSwitchDialog(choice, vm::resolveSwitch)
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = vm.busy,
            ready = vm.stage is Stage.Ready,
            onSend = {
                buzz()
                vm.send(input)
                input = ""
            },
            onStop = {
                buzz()
                vm.stop()
            }
        )
    }
}

/**
 * What the model is doing, above the messages, with the one action that helps. The
 * chat itself never disappears behind a setup page.
 */
@Composable
private fun StatusStrip(vm: ChatViewModel, model: ModelSpec) {
    val scheme = MaterialTheme.colorScheme
    val stage = vm.stage

    androidx.compose.animation.AnimatedVisibility(
        visible = stage !is Stage.Ready,
        enter = expandVertically(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) + fadeIn(tween(Motion.NORMAL)),
        exit = shrinkVertically(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) + fadeOut(tween(Motion.QUICK))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (stage) {
                is Stage.Loading -> {
                    var seconds by remember(vm.loadStartedAt) { mutableIntStateOf(0) }
                    LaunchedEffect(vm.loadStartedAt) {
                        while (true) {
                            seconds = ((android.os.SystemClock.elapsedRealtime() - vm.loadStartedAt) / 1000).toInt()
                            delay(1000)
                        }
                    }
                    StripText(stringResource(R.string.chat_loading, vm.target.label) + if (seconds > 2) stringResource(R.string.chat_s, seconds) else "")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = scheme.primary,
                        trackColor = scheme.outline
                    )
                }

                is Stage.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StripText(
                            if (stage.verifying) stringResource(R.string.setup_verifying_title)
                            else stringResource(R.string.chat_downloading, vm.target.label, (stage.fraction * 100).toInt()),
                            Modifier.weight(1f)
                        )
                        StripAction(stringResource(R.string.chat_view), vm::showDownload)
                    }
                    LinearProgressIndicator(
                        progress = { stage.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = scheme.primary,
                        trackColor = scheme.outline
                    )
                }

                is Stage.NeedsModel -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StripText(stringResource(R.string.chat_isn_t_on_this_phone, model.label), Modifier.weight(1f))
                    StripAction(stringResource(R.string.chat_download)) { vm.openDownload(model) }
                }

                is Stage.Broken -> {
                    Text(
                        stage.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error
                    )
                    Row {
                        when (stage.fix) {
                            Fix.RETRY_LOAD -> StripAction(stringResource(R.string.chat_try_again), vm::retry)
                            Fix.RESUME_DOWNLOAD -> StripAction(stringResource(R.string.chat_continue_download)) { vm.openDownload(vm.target) }
                            Fix.REDOWNLOAD -> StripAction(stringResource(R.string.chat_download_again)) { vm.openDownload(vm.target) }
                        }
                        if (vm.useGpu && stage.fix == Fix.RETRY_LOAD) {
                            StripAction(stringResource(R.string.chat_use_cpu_instead)) { vm.updateUseGpu(false) }
                        }
                    }
                }

                Stage.Ready -> Unit
            }
        }
    }
}

@Composable
private fun StripText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun StripAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp)
    )
}

@Composable
private fun ChatEmptyState(modelLabel: String, ready: Boolean) {
    Column {
        Wordmark(size = 44)
        Spacer(Modifier.height(10.dp))
        Text(
            // Only claim the model is running when it is; the strip above covers the rest.
            if (ready) stringResource(R.string.chat_running_on_this_phone_nothing, modelLabel)
            else stringResource(R.string.chat_everything_here_stays_on_this, modelLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
        )
    }
}

@Composable
private fun ContextNotice(dropped: Int) {
    ContextNotice(
        pluralStringResource(R.plurals.chat_earlier_messages_dropped, dropped, dropped)
    )
}

@Composable
private fun ContextNotice(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
        )
    }
}

/**
 * Switches which model answers in this chat. Models that are not downloaded are
 * still offered — picking one takes you to the download screen rather than
 * pretending the option doesn't exist.
 */
@Composable
private fun ModelPicker(
    current: ModelSpec,
    installed: Set<String>,
    onPick: (ModelSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceVariant,
        title = {
            Text(
                stringResource(R.string.chat_answer_with),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
        },
        text = {
            Column {
                Models.ALL.forEach { model ->
                    val selected = model.id == current.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(model) }
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (selected) scheme.primary else Color.Transparent,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            model.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (model.id in installed) "ready" else stringResource(R.string.chat_mb, model.approxMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (model.id in installed) scheme.primary
                            else scheme.onSurfaceVariant.copy(alpha = 0.64f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_close), color = scheme.onSurfaceVariant)
            }
        }
    )
}

/** Rewrites one of your messages and asks again from that point. */
@Composable
private fun EditMessageDialog(initial: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var draft by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceVariant,
        title = { DialogTitle(stringResource(R.string.chat_edit_your_message)) },
        text = {
            Column {
                EditorField(draft) { draft = it }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.chat_everything_after_this_message_will),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = { onSend(draft) }) {
                Text(stringResource(R.string.chat_send_again), color = scheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel), color = scheme.onSurfaceVariant) }
        }
    )
}

/** Shown when a chat was held with a different model from the one loaded. */
@Composable
private fun ModelSwitchDialog(choice: ModelSwitch, onChoose: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { onChoose(false) },
        containerColor = scheme.surfaceVariant,
        title = { DialogTitle(stringResource(R.string.chat_this_chat_used, choice.chatModel.label)) },
        text = {
            Text(
                stringResource(R.string.chat_is_loaded_right_now_switching, choice.loaded.label),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoose(true) }) {
                Text(stringResource(R.string.chat_switch_to, choice.chatModel.label), color = scheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false) }) {
                Text(stringResource(R.string.chat_keep, choice.loaded.label), color = scheme.onSurfaceVariant)
            }
        }
    )
}

/** Speed figures under a reply, in debug mode only. */
@Composable
private fun SpeedLine(stats: String) {
    Text(
        stats,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    )
}
