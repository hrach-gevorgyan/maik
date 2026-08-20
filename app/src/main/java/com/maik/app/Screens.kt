package com.maik.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/* ================= conversation list ================= */

@Composable
fun ConversationListScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Header
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
            IconCircle(onClick = vm::openSettings) { Gear(scheme.onBackground) }
        }
        HorizontalLine()

        if (vm.conversations.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SearchField(vm.query) { vm.query = it }
            }
        }

        Box(Modifier.weight(1f)) {
            if (vm.conversations.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Wordmark(size = 56)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "No conversations yet.\nEverything you start stays on this phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else if (vm.visibleConversations.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Nothing matches \"${vm.query.trim()}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(vm.visibleConversations, key = { it.id }) { convo ->
                        ConversationRow(
                            convo = convo,
                            onOpen = { vm.open(convo.id) },
                            onLongPress = { menuFor = convo }
                        )
                        HorizontalLine()
                    }
                }
            }

            // New chat
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .clickable(onClick = vm::newChat)
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

    menuFor?.let { convo ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = scheme.surfaceVariant,
            title = {
                Text(
                    convo.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
            },
            text = {
                Text(
                    "${convo.messages.size} messages · ${relativeTime(convo.updatedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = convo
                    menuFor = null
                }) { Text("Rename", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.delete(convo.id)
                    menuFor = null
                }) { Text("Delete", color = Color(0xFFFF9BA6)) }
            }
        )
    }

    renaming?.let { convo ->
        var draft by remember(convo.id) { mutableStateOf(convo.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = scheme.surfaceVariant,
            title = {
                Text(
                    "Rename",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
            },
            text = { InlineField(draft) { draft = it } },
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
private fun ConversationRow(
    convo: Conversation,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClick(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                    color = scheme.onSurfaceVariant.copy(alpha = 0.42f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            relativeTime(convo.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

/* ================= settings ================= */

@Composable
fun SettingsScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val installed = vm.installedModels()
    var confirmWipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings", onBack = vm::openList)

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp)
        ) {
            item {
                SectionLabel("MODEL")
                Spacer(Modifier.height(4.dp))
            }

            items(Models.ALL, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    selected = model.id == vm.spec.id,
                    downloaded = model.id in installed,
                    onClick = { vm.selectModel(model) }
                )
                Spacer(Modifier.height(10.dp))
            }

            item {
                if (vm.spec.reasoning) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("REASONING")
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        label = if (vm.thinkingEnabled) "Think before answering"
                        else "Answer straight away",
                        detail = if (vm.thinkingEnabled)
                            "Slower to start, better on anything that needs working out."
                        else
                            "Faster, shallower. Good for quick questions.",
                        checked = vm.thinkingEnabled,
                        onChange = vm::setThinking
                    )
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("STORAGE")
                Spacer(Modifier.height(10.dp))
                OutlineButton("Delete downloaded model", vm::deleteModel)
                Spacer(Modifier.height(10.dp))
                OutlineButton("Delete all conversations") { confirmWipe = true }

                Spacer(Modifier.height(30.dp))
                SectionLabel("RUNNING ON")
                Spacer(Modifier.height(10.dp))
                Text(
                    when (vm.backend) {
                        Backend.GPU -> "GPU. The fast path."
                        Backend.CPU -> "CPU. The GPU delegate was refused on this device."
                        Backend.NONE -> "Nothing loaded yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )

                Spacer(Modifier.height(30.dp))
                SectionLabel("ABOUT")
                Spacer(Modifier.height(10.dp))
                Text(
                    "maik runs its model locally through MediaPipe LiteRT. The only " +
                        "network request it ever makes is the one that downloads the " +
                        "model. Your conversations never leave this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = scheme.surfaceVariant,
            title = {
                Text(
                    "Delete everything?",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
            },
            text = {
                Text(
                    "All ${vm.conversations.size} conversations, permanently. " +
                        "There is no backup — that's the point.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    confirmWipe = false
                }) { Text("Delete all", color = Color(0xFFFF9BA6)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("Cancel", color = scheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(18.dp))
            .clickable { onChange(!checked) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                uncheckedThumbColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                uncheckedTrackColor = scheme.surface,
                uncheckedBorderColor = scheme.outline
            )
        )
    }
}

@Composable
private fun ModelCard(
    model: ModelSpec,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (downloaded) {
                Text(
                    "READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )
            } else {
                Text(
                    "${model.approxMb} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row {
            Text(
                "${model.params} · ${model.contextTokens / 1024}K context",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.28f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            model.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

/* ================= first-run setup ================= */

@Composable
fun SetupScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val spec = vm.spec
    var warnMetered by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 56)
        Spacer(Modifier.height(18.dp))

        when (val s = vm.stage) {
            is Stage.NeedsModel -> {
                Text(
                    "maik carries its own brain. Fetch it once, then it works " +
                        "forever with the network off.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(24.dp))
                Models.ALL.forEach { model ->
                    ModelCard(
                        model = model,
                        selected = model.id == spec.id,
                        downloaded = model.id in vm.installedModels(),
                        onClick = { vm.selectModel(model) }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(18.dp))
                BigButton("Download model") {
                    if (vm.onMeteredNetwork()) warnMetered = true else vm.startDownload()
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Use Wi-Fi. Nothing you type is ever uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }

            is Stage.Downloading -> {
                Text(
                    "Downloading",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(20.dp))
                ProgressBar(s.fraction)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${s.bytes / 1024 / 1024} / ${s.total / 1024 / 1024} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(s.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.primary
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Keeps going if you lock the screen or leave the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Spacer(Modifier.height(20.dp))
                OutlineButton("Cancel download", vm::cancelDownload)
            }

            is Stage.Loading -> {
                Text(
                    "Waking up",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(16.dp))
                TypingDots()
            }

            is Stage.Broken -> {
                Text(
                    "That didn't work",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF9BA6)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    s.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(26.dp))
                BigButton("Try again", onClick = vm::retry)
            }

            is Stage.Ready -> Unit
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Back",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = vm::openList)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }

    if (warnMetered) {
        MeteredDialog(
            spec = spec,
            onProceed = {
                warnMetered = false
                vm.startDownload()
            },
            onDismiss = { warnMetered = false }
        )
    }
}

@Composable
private fun MeteredDialog(spec: ModelSpec, onProceed: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceVariant,
        title = {
            Text(
                "You are not on Wi-Fi",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
        },
        text = {
            Text(
                "This will pull ${spec.approxMb} MB over a metered connection. " +
                    "That is a real hole in most data plans.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        confirmButton = {
            TextButton(onClick = onProceed) {
                Text("Download anyway", color = Color(0xFFFF9BA6))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Wait for Wi-Fi", color = scheme.primary)
            }
        }
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
                color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
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

/* ================= small pieces ================= */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    )
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.width(78.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val animated by animateFloatAsState(fraction, tween(300), label = "dl")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun OutlineButton(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun IconCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.background)
            .border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
