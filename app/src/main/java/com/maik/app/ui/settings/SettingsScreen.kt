package com.maik.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.R
import com.maik.app.BuildConfig
import com.maik.app.data.*
import com.maik.app.ui.chat.toast
import com.maik.app.ui.components.*
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
    val title: @Composable () -> String,
    val detail: @Composable (ChatViewModel) -> String
)

private val ENTRIES = listOf(
    Entry(SettingsPage.Models, { stringResource(R.string.settings_models) }) {
        val installed = remember(it.storageVersion) { it.installedModels() }
        if (it.spec.id in installed) stringResource(R.string.settings_in_use, it.spec.label)
        else stringResource(R.string.settings_nothing_downloaded_yet)
    },
    Entry(SettingsPage.Instructions, { stringResource(R.string.settings_instructions) }) {
        val flat = it.systemPrompt.replace('\n', ' ').trim()
        when {
            it.systemPrompt == DEFAULT_SYSTEM_PROMPT -> stringResource(R.string.settings_instructions_default)
            flat.length > 46 -> flat.take(46).trimEnd() + "…"
            else -> flat
        }
    },
    Entry(SettingsPage.Appearance, { stringResource(R.string.settings_appearance) }) {
        when (it.themeMode) {
            ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_the_system)
            ThemeMode.DARK -> stringResource(R.string.settings_dark)
            ThemeMode.LIGHT -> stringResource(R.string.settings_light)
        }
    },
    Entry(SettingsPage.Behaviour, { stringResource(R.string.settings_behaviour) }) {
        buildString {
            append(if (it.hapticsEnabled) stringResource(R.string.settings_vibration_on) else stringResource(R.string.settings_vibration_off))
            append(" · ")
            append(if (it.useGpu) stringResource(R.string.settings_gpu) else stringResource(R.string.settings_cpu))
            if (it.keepCool) append(stringResource(R.string.settings_cool))
        }
    },
    Entry(SettingsPage.Storage, { stringResource(R.string.settings_storage) }) {
        val bytes = remember(it.storageVersion) { it.bytesOnDisk() }
        stringResource(R.string.settings_mb_of_models, fileSize(bytes))
    },
    Entry(SettingsPage.About, { stringResource(R.string.settings_about) }) { stringResource(R.string.settings_version_licence_how_it_works) }
)

@Composable
private fun SettingsMenu(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.settings_settings), onBack = vm::openList)
        LazyColumn(Modifier.fillMaxSize()) {
            items(ENTRIES) { entry ->
                MenuRow(
                    title = entry.title(),
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
    val buzz = tap()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                buzz()
                onClick()
            }
            .semantics(mergeDescendants = true) {}
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
        TopBar(title = stringResource(R.string.settings_models), onBack = vm::leaveModels)
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text(
                    stringResource(R.string.settings_new_chats_use_the_model),
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
            title = { DialogTitle(stringResource(R.string.settings_delete, model.label)) },
            text = {
                Text(
                    stringResource(R.string.settings_this_frees_mb_your_chats, fileSize(model.approxBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(model)
                    confirmDelete = null
                }) { Text(stringResource(R.string.settings_delete_2), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.settings_cancel), color = scheme.onSurfaceVariant)
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

    val borderColor by androidx.compose.animation.animateColorAsState(
        if (inUse) scheme.primary else scheme.outline, tween(Motion.NORMAL), label = "modelBorder"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        if (inUse) 2.dp else 1.dp, tween(Motion.NORMAL), label = "modelBorderWidth"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(Motion.NORMAL))
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (inUse) {
                Spacer(Modifier.width(8.dp))
                Tag(stringResource(R.string.settings_in_use_2))
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_mb, fileSize(model.approxBytes)),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.settings_k_context,
                model.params,
                java.text.NumberFormat.getIntegerInstance().format((model.contextTokens * 3 / 4) / 100 * 100)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(model.blurbRes), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        if (model.heavy) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_heavy_model),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        }

        if (tooLittleRam && !installed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_this_phone_may_not_have),
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
                downloading -> RowAction(stringResource(R.string.settings_view_download)) { vm.openDownload(model) }
                !installed -> RowAction(stringResource(R.string.settings_download)) { vm.openDownload(model) }
                !inUse -> RowAction(stringResource(R.string.settings_use_for_new_chats)) { vm.selectModel(model) }
            }
            Spacer(Modifier.weight(1f))
            if (installed && !downloading && !vm.isLoadingModel) {
                RowAction(stringResource(R.string.settings_delete_2), scheme.error, onDelete)
            }
        }
        val partial = remember(vm.storageVersion, downloading) { vm.partialBytes(model) }
        if (!installed && !downloading && partial > 0) {
            Text(
                stringResource(R.string.settings_partial_present, fileSize(partial), fileSize(model.approxBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            RowAction(stringResource(R.string.settings_remove_partial), scheme.error) { vm.removePartialDownload(model) }
        }
        if (installed && vm.isLoadingModel) {
            Text(
                stringResource(R.string.settings_wait_for_load),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        }
    }
}

@Composable
private fun RowAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val buzz = tap()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) {
                buzz()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 14.dp)
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
        TopBar(title = stringResource(R.string.settings_appearance), onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .selectableGroup()
        ) {
            Text(
                stringResource(R.string.settings_dark_is_the_design_maik),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            Spacer(Modifier.height(18.dp))

            listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_follow_the_system),
                ThemeMode.DARK to stringResource(R.string.settings_dark),
                ThemeMode.LIGHT to stringResource(R.string.settings_light)
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
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.RadioButton) {
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
    var draft by rememberSaveable { mutableStateOf(vm.systemPrompt) }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    // The arrow, the system back gesture and Save all keep what was typed. Unchanged
    // instructions aren't re-saved, because saving restarts the conversation.
    fun leave() {
        if (draft.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT } != vm.systemPrompt) {
            vm.updateSystemPrompt(draft)
            toast(context, context.getString(R.string.settings_instructions_saved))
        }
        vm.openSettingsPage(SettingsPage.Root)
    }
    // Only while this is the page: during the exit animation it is still composed.
    androidx.activity.compose.BackHandler(enabled = vm.settingsPage == SettingsPage.Instructions) { leave() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.settings_instructions), onBack = ::leave)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.settings_a_standing_note_handed_to),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            Spacer(Modifier.height(18.dp))
            EditorField(
                draft,
                label = stringResource(R.string.settings_instructions),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                )
            ) { draft = it }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.settings_character_count, draft.length, draft.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
                Spacer(Modifier.weight(1f))
                if (draft != DEFAULT_SYSTEM_PROMPT) {
                    RowAction(stringResource(R.string.settings_reset)) { draft = DEFAULT_SYSTEM_PROMPT }
                }
            }
            Spacer(Modifier.height(14.dp))
            BigButton(stringResource(R.string.settings_save)) { leave() }
        }
    }
}

@Composable
private fun StoragePage(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    // Outside the list: a scope remembered in an item dies when the item scrolls out,
    // which would abandon a backup halfway through writing it.
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }
    // Reading the disk is not observable state; this is what makes a deletion
    // actually disappear from the list.
    val onDisk = remember(vm.storageVersion) { vm.bytesOnDisk() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.settings_storage), onBack = { vm.openSettingsPage(SettingsPage.Root) })
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text(
                    stringResource(R.string.settings_mb_of_models_on_this, fileSize(onDisk)),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(18.dp))
            }
            item {
                OutlineButton(stringResource(R.string.settings_manage_models)) { vm.openSettingsPage(SettingsPage.Models) }
            }
            item {
                val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        // Serialising every chat is the slow part, not the write; the
                        // view model takes its copy here and encodes off the main thread.
                        val json = vm.backupJson()
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                            }.getOrDefault(false)
                        }
                        toast(context, context.getString(if (ok) R.string.settings_backup_done else R.string.settings_backup_failed))
                    }
                }
                val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            }.getOrNull()
                        }
                        val added = text?.let { vm.restoreJson(it) }
                        toast(
                            context,
                            if (added == null) context.getString(R.string.settings_restore_failed)
                            else context.resources.getQuantityString(R.plurals.settings_restore_done, added, added)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.settings_backup_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
                Spacer(Modifier.height(12.dp))
                if (vm.conversations.isNotEmpty()) {
                    OutlineButton(stringResource(R.string.settings_backup_chats)) {
                        save.launch("maik-chats.json")
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlineButton(stringResource(R.string.settings_restore_chats)) {
                    open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                if (vm.conversations.isNotEmpty()) {
                    OutlineButton(
                        stringResource(R.string.settings_delete_all_conversations),
                        color = scheme.error
                    ) { confirmWipe = true }
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle(stringResource(R.string.settings_delete_everything)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.settings_delete_all_detail, vm.conversations.size, vm.conversations.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    confirmWipe = false
                }) { Text(stringResource(R.string.settings_delete_all), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text(stringResource(R.string.settings_cancel), color = scheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun BehaviourPage(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.settings_behaviour), onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            ToggleRow(
                label = stringResource(R.string.settings_vibration),
                detail = stringResource(R.string.settings_a_short_tap_when_you),
                checked = vm.hapticsEnabled,
                onChange = vm::updateHaptics
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = stringResource(R.string.settings_keep_the_phone_cool),
                detail = stringResource(R.string.settings_answers_with_half_the_processor),
                checked = vm.keepCool,
                onChange = vm::updateKeepCool
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = stringResource(R.string.settings_show_speed),
                detail = stringResource(R.string.settings_time_to_first_word_and),
                checked = vm.debugMode,
                onChange = vm::updateDebug
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = stringResource(R.string.settings_use_the_gpu),
                detail = stringResource(R.string.settings_much_faster_at_reading_your),
                checked = vm.useGpu,
                onChange = vm::updateUseGpu
            )
            Spacer(Modifier.height(18.dp))
            Text(
                when (vm.backend) {
                    Backend.GPU -> stringResource(R.string.settings_currently_running_on_the_gpu)
                    Backend.CPU -> stringResource(R.string.settings_currently_running_on_the_cpu)
                    Backend.NONE -> stringResource(R.string.settings_no_model_is_loaded_yet)
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
        TopBar(title = stringResource(R.string.settings_about), onBack = { vm.openSettingsPage(SettingsPage.Root) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Wordmark(size = 40)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_maik_runs_its_model_locally),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
            Spacer(Modifier.height(20.dp))
            LabelledValue(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            LabelledValue(stringResource(R.string.settings_model), vm.spec.label)
            LabelledValue(stringResource(R.string.settings_context), stringResource(R.string.settings_tokens, vm.spec.contextTokens))
            LabelledValue(
                stringResource(R.string.settings_running_on),
                when (vm.backend) {
                    Backend.GPU -> stringResource(R.string.settings_gpu)
                    Backend.CPU -> stringResource(R.string.settings_cpu)
                    Backend.NONE -> stringResource(R.string.settings_nothing_loaded)
                }
            )

            Spacer(Modifier.height(28.dp))
            SectionTitle(stringResource(R.string.settings_what_maik_sends))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_model_downloads_from_huggingface_co),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )

            Spacer(Modifier.height(28.dp))
            SectionTitle(stringResource(R.string.settings_licences))
            Spacer(Modifier.height(8.dp))
            LICENCES.forEach { (what, licence) ->
                LabelledValue(what, licence)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_each_model_carries_the_licence),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
            )

            Spacer(Modifier.height(24.dp))
            OutlineButton(stringResource(R.string.settings_source_code_on_github)) {
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
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() }
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
    val buzz = tap()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(18.dp))
            .toggleable(value = checked, role = Role.Switch) {
                buzz()
                onChange(it)
            }
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
            // The row handles the tap, so the switch isn't a second, separate stop.
            onCheckedChange = null,
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
