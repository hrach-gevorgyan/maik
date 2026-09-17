package com.maik.app.ui.chat

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
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
    val done = tick()
    // A soft tick when a reply lands, so you can look away while it writes.
    var wasBusy by remember { mutableStateOf(vm.busy) }
    LaunchedEffect(vm.busy) {
        if (wasBusy && !vm.busy) done()
        wasBusy = vm.busy
    }
    val count = convo.messages.size
    var pickingModel by remember { mutableStateOf(false) }

    // The list is laid out from the bottom, so a growing reply pushes older messages
    // up by itself — no scrolling on every token, which is what made streaming judder.
    val atBottom by remember { derivedStateOf { !listState.canScrollBackward } }
    val scope = rememberCoroutineScope()

    // Your own message always brings you back down; a reply arriving doesn't yank you
    // away from something you scrolled up to read.
    val lastFromUser = convo.messages.lastOrNull()?.fromUser == true
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
                        .clickable(enabled = !vm.busy, onClickLabel = "Change model") { pickingModel = true }
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
                        QuietAction(if (last.isError) "Try again" else "Regenerate") {
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
                        if (text.isEmpty()) TypingDots() else Bubble(Message(text, fromUser = false))
                    }
                }
            }

            for (index in convo.messages.indices.reversed()) {
                val msg = convo.messages[index]
                item(key = "m$index", contentType = if (msg.fromUser) 0 else 1) {
                    Column(Modifier.animateItem(placementSpec = null, fadeOutSpec = null)) {
                        Bubble(msg)
                        if (vm.debugMode && msg.stats != null) SpeedLine(msg.stats)
                    }
                }
            }

            if (vm.dropped > 0) {
                item(key = "dropped") { ContextNotice(vm.dropped) }
            }
            if (convo.messages.isEmpty() && !vm.busy) {
                item(key = "empty") { ChatEmptyState(vm.modelFor(convo).label, ready = vm.stage is Stage.Ready) }
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
                        scope.launch { listState.animateScrollToItem(0) }
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
