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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.selection.selectable
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
    val view = androidx.compose.ui.platform.LocalView.current
    val replyReady = stringResource(R.string.chat_reply_ready)
    LaunchedEffect(vm.busy) {
        if (wasBusy && !vm.busy) {
            done()
            // TalkBack users otherwise hear nothing when an answer finishes.
            @Suppress("DEPRECATION")
            view.announceForAccessibility(replyReady)
        }
        wasBusy = vm.busy
    }
    val count = convo.messages.size
    var pickingModel by remember { mutableStateOf(false) }
    val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val reader = rememberReadAloud()
    // Voice typing through the phone's own recogniser, which works offline where its
    // language pack is installed. Whatever it heard goes into the message box to check.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            input = if (input.isBlank()) heard else input.trimEnd() + " " + heard
        }
    }
    val photoContext = LocalContext.current
    var choosingPhoto by remember { mutableStateOf(false) }
    val photoFailed = stringResource(R.string.photo_failed)
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.attachPhoto(uri) {
            android.widget.Toast.makeText(photoContext, photoFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.attachPhoto(uri) {
            android.widget.Toast.makeText(photoContext, photoFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val voicePrompt = stringResource(R.string.voice_prompt)
    val voiceUnavailable = stringResource(R.string.voice_unavailable)
    var searching by rememberSaveable(convo.id) { mutableStateOf(false) }
    var findQuery by rememberSaveable(convo.id) { mutableStateOf("") }
    var findPosition by rememberSaveable(convo.id) { mutableStateOf(0) }
    val matches = remember(convo.messages, findQuery) { findInMessages(convo.messages, findQuery) }
    // Newest match first: that's the one nearest the bottom, where you already are.
    val currentMatch = matches.getOrNull(matches.size - 1 - findPosition.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
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
        if (searching) {
            FindBar(
                query = findQuery,
                onQuery = {
                    findQuery = it
                    findPosition = 0
                },
                count = matches.size,
                position = findPosition,
                onPrevious = { if (matches.isNotEmpty()) findPosition = (findPosition + 1) % matches.size },
                onNext = { if (matches.isNotEmpty()) findPosition = (findPosition - 1 + matches.size) % matches.size },
                onClose = {
                    searching = false
                    findQuery = ""
                    findPosition = 0
                }
            )
            androidx.activity.compose.BackHandler {
                searching = false
                findQuery = ""
            }
        } else TopBar(
            title = convo.title,
            onBack = vm::openList,
            trailing = {
                if (convo.messages.isNotEmpty()) {
                    MaikIconButton(description = stringResource(R.string.find_in_chat), onClick = { searching = true }) {
                        Magnifier(MaterialTheme.colorScheme.onBackground)
                    }
                }
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

        LaunchedEffect(currentMatch, searching) {
            val target = currentMatch ?: return@LaunchedEffect
            if (!searching) return@LaunchedEffect
            val last = convo.messages.lastOrNull()
            val above = (if (!vm.busy && last != null && vm.stage is Stage.Ready) 1 else 0) +
                (if (vm.stoppedForHeat) 1 else 0) +
                (if (vm.busy) 1 else 0)
            listState.animateScrollToItem(above + (convo.messages.lastIndex - target))
        }

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
            if (!vm.busy && last != null && vm.stage is Stage.Ready) {
                item(key = "again", contentType = 3) {
                    Box(Modifier.animateItem(fadeInSpec = tween(Motion.NORMAL, delayMillis = 120), placementSpec = null)) {
                        when {
                            last.fromUser -> QuietAction(stringResource(R.string.chat_get_an_answer)) {
                                buzz()
                                vm.answerLast()
                            }

                            last.truncated && !last.isError -> Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                            ) {
                                QuietAction(stringResource(R.string.chat_continue), fill = false) {
                                    buzz()
                                    vm.continueReply()
                                }
                                QuietAction(stringResource(R.string.chat_regenerate), fill = false) {
                                    buzz()
                                    vm.regenerate()
                                }
                            }

                            else -> QuietAction(if (last.isError) stringResource(R.string.chat_try_again) else stringResource(R.string.chat_regenerate)) {
                                buzz()
                                vm.regenerate()
                            }
                        }
                    }
                }
            }

            // Heat is about the reply just written, so the notice sits with it, at the
            // bottom, and goes away when tapped.
            if (vm.stoppedForHeat) {
                item(key = "heat") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_heat_dismiss)) {
                                vm.dismissHeatNotice()
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        ContextNotice(stringResource(R.string.chat_maik_stopped_early_because_the))
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
                            if (waiting) {
                                val answering = stringResource(R.string.chat_maik_is_answering)
                                Box(Modifier.semantics { contentDescription = answering }) { TypingDots() }
                            } else {
                                Bubble(Message(text, fromUser = false))
                            }
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
                                Bubble(
                                    msg,
                                    onLongPress = { menuFor = index },
                                    match = when {
                                        !searching || index !in matches -> Match.None
                                        index == currentMatch -> Match.Current
                                        else -> Match.Other
                                    }
                                )
                                if (vm.debugMode && msg.stats != null) SpeedLine(msg.stats)
                            }
                        }
                        if (index >= alreadyThere) AppearsIn { body() } else body()
                    }
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
                ChatEmptyState(
                    vm.modelFor(convo).label,
                    ready = vm.stage is Stage.Ready,
                    onStarter = { prefix ->
                        input = prefix
                        // Straight into typing the rest, keyboard up.
                        scope.launch {
                            withFrameNanos { }
                            runCatching { composerFocus.requestFocus() }
                        }
                    }
                )
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
                    if (!msg.fromUser && !msg.isError) {
                        val reading = reader.speakingAt == msg.at
                        add(SheetAction(stringResource(if (reading) R.string.voice_stop_reading else R.string.voice_read_aloud)) {
                            if (reading) {
                                reader.stop()
                            } else if (reader.available) {
                                reader.speak(msg.at, msg.text)
                            } else {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.voice_no_speech_engine), android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            menuFor = null
                        })
                    }
                    if (msg.fromUser && !vm.busy && vm.stage is Stage.Ready) {
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

        if (choosingPhoto) {
            ActionSheet(
                actions = listOf(
                    SheetAction(stringResource(R.string.photo_take)) {
                        choosingPhoto = false
                        val file = vm.cameraTarget()
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.maik.app.files", file)
                        cameraUri = uri
                        runCatching { takePhoto.launch(uri) }
                    },
                    SheetAction(stringResource(R.string.photo_choose)) {
                        choosingPhoto = false
                        pickPhoto.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                ),
                onDismiss = { choosingPhoto = false }
            )
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
            if (choice.chatId == convo.id) ModelSwitchDialog(choice, onDismiss = vm::dismissSwitch, onChoose = vm::resolveSwitch)
        }

        AnswerLengthToggle(vm.shortAnswers, vm::toggleAnswerLength)

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = vm.busy,
            ready = vm.stage is Stage.Ready,
            focusRequester = composerFocus,
            photo = vm.pendingPhoto,
            onAttach = if (vm.canSeePhotos && vm.stage is Stage.Ready) ({ choosingPhoto = true }) else null,
            onRemovePhoto = vm::removePendingPhoto,
            onVoice = {
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                runCatching { voice.launch(intent) }.onFailure {
                    android.widget.Toast.makeText(context, voiceUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            waitingHint = when (vm.stage) {
                is Stage.NeedsModel -> stringResource(R.string.chat_hint_needs_model)
                is Stage.Broken -> stringResource(R.string.chat_hint_broken)
                else -> stringResource(R.string.chat_waiting_for_the_model)
            },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyState(modelLabel: String, ready: Boolean, onStarter: (String) -> Unit) {
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
        Spacer(Modifier.height(20.dp))
        // The things people most often want from a phone with no signal. Each one starts
        // the message rather than sending it, so it can be finished in your own words.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                R.string.starter_translate to R.string.starter_translate_prefix,
                R.string.starter_explain to R.string.starter_explain_prefix,
                R.string.starter_summarise to R.string.starter_summarise_prefix,
                R.string.starter_message to R.string.starter_message_prefix
            ).forEach { (label, prefix) ->
                val text = stringResource(prefix)
                StarterChip(stringResource(label)) { onStarter(text) }
            }
        }
    }
}

@Composable
private fun StarterChip(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = scheme.onSurface,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .border(1.dp, scheme.outline, CircleShape)
            .clickable(role = Role.Button) {
                buzz()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/** Short or detailed replies, one tap to switch; it applies from the next message. */
@Composable
private fun AnswerLengthToggle(short: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    val hint = stringResource(R.string.style_toggle_hint)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Start) {
        AnimatedContent(
            targetState = short,
            transitionSpec = { fadeIn(tween(Motion.NORMAL)) togetherWith fadeOut(tween(Motion.QUICK)) },
            label = "answerLength"
        ) { isShort ->
            Text(
                stringResource(if (isShort) R.string.style_short else R.string.style_detailed),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Switch, onClickLabel = hint) {
                        buzz()
                        onToggle()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
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
                            .selectable(selected = selected, role = Role.RadioButton) { onPick(model) }
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
                            if (model.id in installed) stringResource(R.string.chat_model_on_phone) else stringResource(R.string.chat_mb, fileSize(model.approxBytes)),
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
private fun ModelSwitchDialog(choice: ModelSwitch, onDismiss: () -> Unit, onChoose: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
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

/** Search within the open chat: the query, where you are among the matches, and up/down. */
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    position: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val label = stringResource(R.string.find_in_chat)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MaikIconButton(description = stringResource(R.string.find_close), onClick = onClose) {
            ChevronLeft(scheme.onBackground)
        }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (query.isEmpty()) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.64f))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = label }
            )
        }
        if (query.isNotBlank()) {
            Text(
                if (count == 0) stringResource(R.string.find_no_matches)
                else stringResource(R.string.find_position, position + 1, count),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            )
            MaikIconButton(description = stringResource(R.string.find_previous), onClick = onPrevious) {
                ChevronVertical(if (count > 1) scheme.onBackground else scheme.outline, up = true)
            }
            MaikIconButton(description = stringResource(R.string.find_next), onClick = onNext) {
                ChevronVertical(if (count > 1) scheme.onBackground else scheme.outline, up = false)
            }
        }
    }
    HorizontalLine()
}
