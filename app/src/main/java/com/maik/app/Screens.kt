package com.maik.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

private val DANGER = Color(0xFFFF9BA6)

/* ================= conversation list ================= */

@Composable
fun ConversationListScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }

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
            IconCircle(onClick = vm::openSettings) { Sliders(scheme.onBackground) }
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
                        color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(vm.visibleConversations, key = { it.id }) { convo ->
                        ConversationRow(
                            convo = convo,
                            onOpen = { vm.open(convo.id) },
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuFor = convo
                            }
                        )
                        HorizontalLine()
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.newChat()
                    }
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
            title = { DialogTitle(convo.title) },
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
                }) { Text("Delete", color = DANGER) }
            }
        )
    }

    renaming?.let { convo ->
        var draft by remember(convo.id) { mutableStateOf(convo.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Rename") },
            text = { InlineField(draft, singleLine = true) { draft = it } },
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
private fun EmptyList() {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
    var editingPrompt by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings", onBack = vm::openList)

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp)
        ) {
            item {
                SectionLabel("MODEL")
                Spacer(Modifier.height(4.dp))
                Text(
                    "New chats use this one. Existing chats keep the model they started with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(22.dp))
                SectionLabel("INSTRUCTIONS")
                Spacer(Modifier.height(4.dp))
                Text(
                    "A standing note handed to the model before every conversation. " +
                        "It sets the tone and the ground rules — the model follows it " +
                        "without you having to repeat yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, scheme.outline, RoundedCornerShape(18.dp))
                        .clickable { editingPrompt = true }
                        .padding(18.dp)
                ) {
                    Text(
                        vm.systemPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary
                    )
                }

                if (Models.ALL.any { it.reasoning }) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("REASONING")
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        label = "Think before answering",
                        detail = "Slower to start, better on anything that needs working out. " +
                            "Only applies to models that support it.",
                        checked = vm.thinkingEnabled,
                        onChange = vm::setThinking
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("RUNNING ON")
                Spacer(Modifier.height(10.dp))
                Text(
                    when (vm.backend) {
                        Backend.GPU -> "GPU — the fast path."
                        Backend.CPU -> "CPU — the GPU delegate was refused on this device."
                        Backend.NONE -> "Nothing loaded yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )

                Spacer(Modifier.height(22.dp))
                SectionLabel("STORAGE")
                Spacer(Modifier.height(4.dp))
                Text(
                    "${vm.bytesOnDisk() / 1024 / 1024} MB of models on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(12.dp))
                OutlineButton("Delete downloaded models") { vm.deleteModel() }
                Spacer(Modifier.height(10.dp))
                OutlineButton("Delete all conversations") { confirmWipe = true }

                Spacer(Modifier.height(30.dp))
                SectionLabel("ABOUT")
                Spacer(Modifier.height(10.dp))
                Text(
                    "maik runs its model locally through MediaPipe LiteRT. The only " +
                        "network request it ever makes is the one that downloads a " +
                        "model. Your conversations never leave this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editingPrompt) {
        var draft by remember { mutableStateOf(vm.systemPrompt) }
        AlertDialog(
            onDismissRequest = { editingPrompt = false },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Instructions") },
            text = { InlineField(draft, singleLine = false) { draft = it } },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSystemPrompt(draft)
                    editingPrompt = false
                }) { Text("Save", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSystemPrompt(DEFAULT_SYSTEM_PROMPT)
                    editingPrompt = false
                }) { Text("Reset", color = scheme.onSurfaceVariant) }
            }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Delete everything?") },
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
                }) { Text("Delete all", color = DANGER) }
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
            Text(label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
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
            Text(model.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                if (downloaded) "ON DEVICE" else "${model.approxMb} MB",
                style = MaterialTheme.typography.labelSmall,
                color = if (downloaded) scheme.primary
                else scheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${model.params} · ${model.contextTokens / 1024}K context",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.28f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            model.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

/* ================= setup / download ================= */

@Composable
fun SetupScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val spec = vm.spec
    var warnMetered by remember { mutableStateOf(false) }

    // Asked only when a download is about to start, and only because the progress
    // notification needs it. Nothing greets a first-time user with a permission box.
    val notifications = notificationRequester()

    fun begin() {
        notifications()
        vm.startDownload()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollIfNeeded()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 52)
        Spacer(Modifier.height(18.dp))

        when (val s = vm.stage) {
            is Stage.NeedsModel -> {
                Text(
                    "maik carries its own brain. Fetch it once, then it works " +
                        "forever with the network off.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(22.dp))
                SpecRow("MODEL", spec.label)
                SpecRow("SIZE", "${spec.approxMb} MB, once")
                SpecRow("AFTER", "Fully offline")
                Spacer(Modifier.height(26.dp))
                BigButton("Download ${spec.label}") {
                    if (vm.onMeteredNetwork()) warnMetered = true else begin()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Wi-Fi recommended. Nothing you type is ever uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Spacer(Modifier.height(20.dp))
                QuietAction("Choose a different model", vm::openSettings)
            }

            is Stage.Downloading -> {
                val started = s.bytes > 0
                Text(
                    if (started) "Downloading ${spec.label}" else "Starting…",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(20.dp))
                ProgressBar(s.fraction, indeterminate = !started)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        if (started) "${s.bytes / 1024 / 1024} / ${s.total / 1024 / 1024} MB"
                        else "Connecting",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.weight(1f))
                    if (started) {
                        Text(
                            "${(s.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Keeps going if you lock the screen or leave the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Spacer(Modifier.height(22.dp))
                OutlineButton("Cancel", vm::cancelDownload)
            }

            is Stage.Loading -> {
                Text(
                    "Warming up ${spec.label}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "First load takes a moment. It's quicker after this.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(20.dp))
                TypingDots()
            }

            is Stage.Broken -> BrokenState(vm, s, onRefetch = ::begin)

            is Stage.Ready -> Unit
        }

        Spacer(Modifier.height(24.dp))
        QuietAction("Back", vm::openList)
    }

    if (warnMetered) {
        AlertDialog(
            onDismissRequest = { warnMetered = false },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("You are not on Wi-Fi") },
            text = {
                Text(
                    "This will pull ${spec.approxMb} MB over a metered connection. " +
                        "That is a real hole in most data plans.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    warnMetered = false
                    begin()
                }) { Text("Download anyway", color = DANGER) }
            },
            dismissButton = {
                TextButton(onClick = { warnMetered = false }) {
                    Text("Wait for Wi-Fi", color = scheme.primary)
                }
            }
        )
    }
}

@Composable
private fun BrokenState(vm: ChatViewModel, stage: Stage.Broken, onRefetch: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showDetail by remember { mutableStateOf(false) }

    Text(
        "That didn't work",
        style = MaterialTheme.typography.headlineSmall,
        color = DANGER
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stage.summary,
        style = MaterialTheme.typography.bodyLarge,
        color = scheme.onSurfaceVariant.copy(alpha = 0.65f)
    )

    if (stage.detail.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            if (showDetail) "Hide details" else "Show details",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showDetail = !showDetail }
                .padding(vertical = 4.dp)
        )
        if (showDetail) {
            Spacer(Modifier.height(8.dp))
            Text(
                // Engine messages are long and full of file paths; the first line
                // is the only part that ever means anything.
                stage.detail.lineSequence().first().take(300),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }

    Spacer(Modifier.height(26.dp))
    if (stage.refetch) {
        BigButton("Download again") {
            vm.deleteModel()
            onRefetch()
        }
        Spacer(Modifier.height(12.dp))
        QuietAction("Try a different model", vm::openSettings)
    } else {
        BigButton("Try again", onClick = vm::retry)
    }
}

/* ================= small pieces ================= */

/**
 * Requests notification permission on demand. Returns a function to call at the
 * moment the permission is actually needed — never on first launch, where a
 * permission box before any explanation is just noise.
 */
@Composable
private fun notificationRequester(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

/** Long error text must not push the buttons off a short screen. */
@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable
private fun DialogTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

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
private fun ProgressBar(fraction: Float, indeterminate: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val animated by animateFloatAsState(fraction, tween(300), label = "dl")

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant)
    ) {
        if (indeterminate) {
            val t = rememberInfiniteTransition(label = "sweep")
            val x by t.animateFloat(
                initialValue = -0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
                label = "x"
            )
            BoxWithConstraints {
                Box(
                    Modifier
                        .offset(x = maxWidth * x)
                        .fillMaxHeight()
                        .width(maxWidth * 0.3f)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(scheme.primary)
            )
        }
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

@Composable
private fun InlineField(value: String, singleLine: Boolean, onValueChange: (String) -> Unit) {
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
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 8,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
