package com.maik.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings as a menu of small pages rather than one long scroll: each page holds
 * one decision, and the titles say what the decision is.
 */
@Composable
fun SettingsScreen(vm: ChatViewModel) {
    AnimatedContent(
        targetState = vm.settingsPage,
        transitionSpec = {
            if (targetState == SettingsPage.Root) backward() else forward()
        },
        label = "settings"
    ) { page ->
        when (page) {
            SettingsPage.Root -> SettingsMenu(vm)
            SettingsPage.Models -> ModelsPage(vm)
            SettingsPage.Appearance -> AppearancePage(vm)
            SettingsPage.Instructions -> InstructionsPage(vm)
            SettingsPage.Behaviour -> BehaviourPage(vm)
            SettingsPage.Storage -> StoragePage(vm)
            SettingsPage.About -> AboutPage(vm)
        }
    }
}

/* ---------- menu ---------- */

private data class Entry(
    val page: SettingsPage,
    val title: String,
    val detail: (ChatViewModel) -> String
)

private val ENTRIES = listOf(
    Entry(SettingsPage.Models, "Model") { it.spec.label },
    Entry(SettingsPage.Instructions, "Instructions") {
        it.systemPrompt.replace('\n', ' ').take(46).trim() + "…"
    },
    Entry(SettingsPage.Appearance, "Appearance") {
        when (it.themeMode) {
            ThemeMode.SYSTEM -> "Follow the system"
            ThemeMode.DARK -> "Dark"
            ThemeMode.LIGHT -> "Light"
        }
    },
    Entry(SettingsPage.Behaviour, "Behaviour") {
        buildString {
            append(if (it.hapticsEnabled) "Vibration on" else "Vibration off")
            append(" · ")
            append(if (it.useGpu) "GPU" else "CPU")
        }
    },
    Entry(SettingsPage.Storage, "Storage") { "${it.bytesOnDisk() / 1024 / 1024} MB of models" },
    Entry(SettingsPage.About, "About") { "Version, licence, how it works" }
)

@Composable
private fun SettingsMenu(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings", onBack = vm::openList)
        LazyColumn(Modifier.fillMaxSize()) {
            items(ENTRIES) { entry ->
                MenuRow(
                    title = entry.title,
                    detail = entry.detail(vm),
                    onClick = { vm.openSettingsPage(entry.page) }
                )
                HorizontalLine()
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, detail: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.rotate(180f)) {
            ChevronLeft(scheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}

/* ---------- pages ---------- */

@Composable
private fun ModelsPage(vm: ChatViewModel) {
    val installed = remember(vm.storageVersion) { vm.installedModels() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Model", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text(
                    "New chats use this one. Existing chats keep the model they " +
                        "started with — switch that from the chat's own header.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(18.dp))
            }
            items(Models.ALL) { model ->
                ModelCard(
                    model = model,
                    selected = model.id == vm.spec.id,
                    downloaded = model.id in installed,
                    onClick = { vm.selectModel(model) }
                )
                Spacer(Modifier.height(10.dp))
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is the whole list: every other on-device model worth " +
                        "having sits behind a sign-in on Hugging Face.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    model: ModelSpec,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
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
            "${model.params} · ${model.contextTokens / 1024}K context" +
                if (model.reasoning) " · reasons first" else "",
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

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun AppearancePage(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Appearance", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(Modifier.padding(20.dp)) {
            Text(
                "Dark is the design maik was drawn for. Light exists because phones " +
                    "get used outdoors.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(18.dp))

            listOf(
                ThemeMode.SYSTEM to "Follow the system",
                ThemeMode.DARK to "Dark",
                ThemeMode.LIGHT to "Light"
            ).forEach { (mode, label) ->
                ChoiceRow(
                    label = label,
                    selected = vm.themeMode == mode,
                    onClick = { vm.updateThemeMode(mode) }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    val buzz = tap()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outline,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = source, indication = null) {
                buzz()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Spacer(Modifier.weight(1f))
        val dot by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = tween(Motion.NORMAL),
            label = "tick"
        )
        Box(
            Modifier
                .size(10.dp)
                .scale(dot)
                .background(scheme.primary, CircleShape)
        )
    }
}

@Composable
private fun InstructionsPage(vm: ChatViewModel) {
    var draft by remember { mutableStateOf(vm.systemPrompt) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Instructions", onBack = {
            vm.updateSystemPrompt(draft)
            vm.openSettingsPage(SettingsPage.Root)
        })
        Column(Modifier.padding(20.dp)) {
            Text(
                "A standing note handed to the model before every conversation. It " +
                    "sets the tone and the ground rules, so you don't have to repeat " +
                    "yourself. Changes apply to your next message.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(18.dp))
            EditorField(draft) { draft = it }
            Spacer(Modifier.height(14.dp))
            Row {
                Text(
                    "${draft.length} characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { draft = DEFAULT_SYSTEM_PROMPT }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            BigButton("Save") {
                vm.updateSystemPrompt(draft)
                vm.openSettingsPage(SettingsPage.Root)
            }
        }
    }
}

@Composable
private fun StoragePage(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    var confirmWipe by remember { mutableStateOf(false) }
    // Reading the disk is not observable state; this is what makes a deletion
    // actually disappear from the list.
    val installed = remember(vm.storageVersion) { vm.installedModels() }
    val onDisk = remember(vm.storageVersion) { vm.bytesOnDisk() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Storage", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text(
                    "${onDisk / 1024 / 1024} MB of models on this device.",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(18.dp))
            }
            items(Models.ALL.filter { it.id in installed }) { model ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        model.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${model.approxMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.deleteModel(model) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                HorizontalLine()
            }
            item {
                Spacer(Modifier.height(24.dp))
                OutlineButton("Delete all conversations") { confirmWipe = true }
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
                }) { Text("Delete all", color = scheme.error) }
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
private fun BehaviourPage(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Behaviour", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(Modifier.padding(20.dp)) {
            ToggleRow(
                label = "Vibration",
                detail = "A short tap when you send, stop, or press and hold.",
                checked = vm.hapticsEnabled,
                onChange = vm::updateHaptics
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = "Use the GPU",
                detail = "Faster when it works. Some drivers refuse it or crash " +
                    "outright, so it stays off unless you ask. If the app dies while " +
                    "loading, this turns itself back off.",
                checked = vm.useGpu,
                onChange = vm::updateUseGpu
            )
            Spacer(Modifier.height(18.dp))
            Text(
                when (vm.backend) {
                    Backend.GPU -> "Currently running on the GPU."
                    Backend.CPU -> "Currently running on the CPU."
                    Backend.NONE -> "No model is loaded yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun AboutPage(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "About", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(Modifier.padding(20.dp)) {
            Wordmark(size = 40)
            Spacer(Modifier.height(16.dp))
            Text(
                "maik runs its model locally through MediaPipe LiteRT. The only " +
                    "network request it ever makes is the one that downloads a model. " +
                    "Your conversations never leave this device, and there is no " +
                    "account, no key and no telemetry.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(20.dp))
            LabelledValue("MODEL", vm.spec.label)
            LabelledValue("CONTEXT", "${vm.spec.contextTokens} tokens")

        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
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
