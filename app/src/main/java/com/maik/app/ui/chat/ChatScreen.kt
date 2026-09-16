package com.maik.app.ui.chat

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maik.app.*
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
    val count = convo.messages.size
    var pickingModel by remember { mutableStateOf(false) }

    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val scope = rememberCoroutineScope()

    // A new message scrolls into view once. While a reply streams, follow it only if
    // the reader was already at the bottom — never drag them down while they read.
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    val streamingLength = vm.streaming.length
    LaunchedEffect(streamingLength) {
        if (vm.busy && atBottom) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
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
                        .clickable(enabled = !vm.busy, onClickLabel = "Change model") { pickingModel = true }
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                )
            }
        )

        StatusStrip(vm, vm.modelFor(convo))

        Box(Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (convo.messages.isEmpty() && !vm.busy) {
                item(key = "empty") { ChatEmptyState(vm.modelFor(convo).label, ready = vm.stage is Stage.Ready) }
            }
            if (vm.dropped > 0) {
                item(key = "dropped") { ContextNotice(vm.dropped) }
            }

            // Keyed by position as well as time: a reply and an error can be stamped in
            // the same millisecond, and duplicate keys crash the list.
            itemsIndexed(
                convo.messages,
                key = { index, msg -> "$index-${msg.at}" },
                contentType = { _, msg -> if (msg.fromUser) 0 else 1 }
            ) { _, msg ->
                Column(Modifier.animateItem()) {
                    Bubble(msg)
                    if (vm.debugMode && msg.stats != null) SpeedLine(msg.stats)
                }
            }

            if (vm.busy) {
                item(key = "live", contentType = 2) {
                    val text = vm.streaming
                    if (text.isEmpty()) TypingDots()
                    else Bubble(Message(text, fromUser = false))
                }
            }

            // Offered only when there is something to replace, and nothing running.
            val last = convo.messages.lastOrNull()
            if (!vm.busy && last?.fromUser == false && vm.stage is Stage.Ready) {
                item(key = "again") {
                    QuietAction(if (last.isError) "Try again" else "Regenerate") {
                        buzz()
                        vm.regenerate()
                    }
                }
            }
        }

        // Scrolled up while a reply grows below: offer the way back rather than
        // pulling the reader down.
        androidx.compose.animation.AnimatedVisibility(
            visible = !atBottom && count > 0,
            enter = fadeIn(tween(Motion.QUICK)),
            exit = fadeOut(tween(Motion.QUICK)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) {
            Text(
                "Jump to latest",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            )
        }
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
        enter = fadeIn(tween(Motion.NORMAL)),
        exit = fadeOut(tween(Motion.NORMAL))
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
                    StripText("Loading ${vm.target.label}" + if (seconds > 2) " · ${seconds}s" else "")
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
                            "Downloading ${vm.target.label} · ${(stage.fraction * 100).toInt()}%",
                            Modifier.weight(1f)
                        )
                        StripAction("View", vm::showDownload)
                    }
                    LinearProgressIndicator(
                        progress = { stage.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = scheme.primary,
                        trackColor = scheme.outline
                    )
                }

                is Stage.NeedsModel -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StripText("${model.label} isn't on this phone yet.", Modifier.weight(1f))
                    StripAction("Download") { vm.openDownload(model) }
                }

                is Stage.Broken -> {
                    Text(
                        stage.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error
                    )
                    Row {
                        when (stage.fix) {
                            Fix.RETRY_LOAD -> StripAction("Try again", vm::retry)
                            Fix.RESUME_DOWNLOAD -> StripAction("Continue download") { vm.openDownload(vm.target) }
                            Fix.REDOWNLOAD -> StripAction("Download again") { vm.openDownload(vm.target) }
                        }
                        if (vm.useGpu && stage.fix == Fix.RETRY_LOAD) {
                            StripAction("Use CPU instead") { vm.updateUseGpu(false) }
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
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp)
    )
}

@Composable
private fun ChatEmptyState(modelLabel: String, ready: Boolean) {
    Column(Modifier.padding(top = 40.dp, bottom = 24.dp)) {
        Wordmark(size = 44)
        Spacer(Modifier.height(10.dp))
        Text(
            // Only claim the model is running when it is; the strip above covers the rest.
            if (ready) "Running $modelLabel on this phone. Nothing you type leaves it."
            else "Everything here stays on this phone. You can start as soon as $modelLabel is ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
        )
    }
}

@Composable
private fun ContextNotice(dropped: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            "$dropped earlier message${if (dropped == 1) "" else "s"} " +
                "no longer fit in the model's memory",
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
                "Answer with",
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
                            if (model.id in installed) "ready" else "${model.approxMb} MB",
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
                Text("Close", color = scheme.onSurfaceVariant)
            }
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
        title = { DialogTitle("This chat used ${choice.chatModel.label}") },
        text = {
            Text(
                "${choice.loaded.label} is loaded right now. Switching reloads the model, " +
                    "which takes a moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoose(true) }) {
                Text("Switch to ${choice.chatModel.label}", color = scheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false) }) {
                Text("Keep ${choice.loaded.label}", color = scheme.onSurfaceVariant)
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
