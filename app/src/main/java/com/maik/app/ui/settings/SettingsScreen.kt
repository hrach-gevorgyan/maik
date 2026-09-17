package com.maik.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.BuildConfig
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

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
    Entry(SettingsPage.Models, "Models") {
        if (it.spec.id in it.installedModels()) "${it.spec.label} in use"
        else "Nothing downloaded yet"
    },
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
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.rotate(180f)) {
            ChevronLeft(scheme.onSurfaceVariant.copy(alpha = 0.64f))
        }
    }
}

/* ---------- pages ---------- */

@Composable
private fun ModelsPage(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val installed = remember(vm.storageVersion) { vm.installedModels() }
    val ram = remember { vm.totalRamBytes() }
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Models", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text(
                    "New chats use the model marked in use. Each model is downloaded once " +
                        "and works offline after that.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
            }
            items(Models.ALL, key = { it.id }) { model ->
                ModelRow(
                    vm = vm,
                    model = model,
                    installed = model.id in installed,
                    tooLittleRam = ram in 1 until model.minRamBytes,
                    onDelete = { confirmDelete = model }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Delete ${model.label}?") },
            text = {
                Text(
                    "This frees ${model.approxMb} MB. Your chats stay; you would need to " +
                        "download the model again to use it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(model)
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
}

/** One model, its state, and the single action that makes sense for it right now. */
@Composable
private fun ModelRow(
    vm: ChatViewModel,
    model: ModelSpec,
    installed: Boolean,
    tooLittleRam: Boolean,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val downloadingId by DownloadBus.modelId.collectAsState()
    val running by DownloadBus.running.collectAsState()
    val progress by DownloadBus.progress.collectAsState()
    val downloading = running && downloadingId == model.id
    val inUse = installed && vm.spec.id == model.id

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .border(
                width = if (inUse) 2.dp else 1.dp,
                color = if (inUse) scheme.primary else scheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (inUse) {
                Spacer(Modifier.width(8.dp))
                Tag("IN USE")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${model.approxMb} MB",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${model.params} · ${model.contextTokens / 1024}K context",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(model.blurb, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)

        if (tooLittleRam && !installed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone may not have enough memory to run it smoothly.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error
            )
        }

        if (downloading) {
            val fraction = progress?.let { if (it.total > 0) it.bytes.toFloat() / it.total else 0f } ?: 0f
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = scheme.primary,
                trackColor = scheme.outline
            )
        }

        Spacer(Modifier.height(6.dp))
        Row {
            when {
                downloading -> RowAction("View download") { vm.openDownload(model) }
                !installed -> RowAction("Download") { vm.openDownload(model) }
                !inUse -> RowAction("Use for new chats") { vm.selectModel(model) }
            }
            Spacer(Modifier.weight(1f))
            if (installed && !downloading) RowAction("Delete", scheme.error, onDelete)
        }
    }
}

@Composable
private fun RowAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp)
    )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
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
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            Spacer(Modifier.height(18.dp))
            EditorField(draft) { draft = it }
            Spacer(Modifier.height(14.dp))
            Row {
                Text(
                    "${draft.length} characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
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
            item {
                OutlineButton("Manage models") { vm.openSettingsPage(SettingsPage.Models) }
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
                label = "Show speed",
                detail = "Time to first word and words per second under each reply. " +
                    "For troubleshooting.",
                checked = vm.debugMode,
                onChange = vm::updateDebug
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = "Use the GPU",
                detail = "Much faster at reading your message. On by default on recent " +
                    "Snapdragon chips. If the app ever crashes while loading, this turns " +
                    "itself off.",
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        }
    }
}

@Composable
private fun AboutPage(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "About", onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Wordmark(size = 40)
            Spacer(Modifier.height(16.dp))
            Text(
                "maik runs its model locally with Google's LiteRT-LM runtime. The only " +
                    "network request it ever makes is the one that downloads a model. " +
                    "Your conversations never leave this device, and there is no " +
                    "account, no key and no telemetry.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            Spacer(Modifier.height(20.dp))
            LabelledValue("VERSION", BuildConfig.VERSION_NAME)
            LabelledValue("MODEL", vm.spec.label)
            LabelledValue("CONTEXT", "${vm.spec.contextTokens} tokens")
            LabelledValue(
                "RUNNING ON",
                when (vm.backend) {
                    Backend.GPU -> "GPU"
                    Backend.CPU -> "CPU"
                    Backend.NONE -> "Nothing loaded"
                }
            )

            Spacer(Modifier.height(28.dp))
            SectionTitle("What maik sends")
            Spacer(Modifier.height(8.dp))
            Text(
                "Model downloads from huggingface.co, and nothing else. No analytics, " +
                    "no crash reports, no account. Chats and settings are stored only on " +
                    "this phone, and Android's own backup can copy them to your Google " +
                    "account if you have backup switched on — model files are excluded.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )

            Spacer(Modifier.height(28.dp))
            SectionTitle("Licences")
            Spacer(Modifier.height(8.dp))
            LICENCES.forEach { (what, licence) ->
                LabelledValue(what, licence)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Each model carries the licence of whoever trained it; read it before " +
                    "using a model's output commercially.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )

            Spacer(Modifier.height(24.dp))
            OutlineButton("Source code on GitHub") {
                runCatching<Unit> {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/hrach-gevorgyan/maik")
                        )
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Everything maik is built out of, and what each may be used under. */
private val LICENCES = listOf(
    "LITERT-LM" to "Apache 2.0, Google",
    "ANDROID, COMPOSE" to "Apache 2.0, Google",
    "KOTLIN" to "Apache 2.0, JetBrains",
    "HK GROTESK" to "SIL Open Font Licence 1.1",
    "GEMMA 4 E2B" to "Gemma Terms of Use, Google",
    "LFM2.5 1.2B" to "LFM Open Licence, Liquid AI"
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
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
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                uncheckedThumbColor = scheme.onSurfaceVariant.copy(alpha = 0.64f),
                uncheckedTrackColor = scheme.surface,
                uncheckedBorderColor = scheme.outline
            )
        )
    }
}
