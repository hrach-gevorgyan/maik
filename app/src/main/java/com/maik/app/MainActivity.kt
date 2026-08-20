package com.maik.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@Composable
private fun Root(vm: ChatViewModel = viewModel()) {
    MaikTheme(vm.themeMode) {
      CompositionLocalProvider(LocalHaptics provides vm.hapticsEnabled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Coming back from the notification should land on the download, not
            // on whatever screen happened to be open when you left.
            val downloading by DownloadBus.running.collectAsState()
            LaunchedEffect(downloading) {
                if (downloading && vm.screen == Screen.List) vm.showDownload()
            }

            // Back always means "up one level", never "leave the app mid-chat".
            BackHandler(enabled = vm.screen != Screen.List) { vm.back() }

            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = {
                        if (targetState == Screen.List) backward() else forward()
                    },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.List -> ConversationListScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Setup -> SetupScreen(vm)
                        Screen.Chat -> when (vm.stage) {
                            is Stage.Ready -> ChatScreen(vm)
                            else -> SetupScreen(vm)
                        }
                    }
                }
            }
        }
      }
    }
}

/* ================= chat ================= */

@Composable
private fun ChatScreen(vm: ChatViewModel) {
    val convo = vm.current ?: return
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val buzz = tap()
    val count = convo.messages.size
    var pickingModel by remember { mutableStateOf(false) }

    LaunchedEffect(count, vm.busy, vm.streaming.length) {
        val items = count + if (vm.busy) 1 else 0
        if (items > 0) listState.animateScrollToItem(items - 1)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !vm.busy) { pickingModel = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (convo.messages.isEmpty() && !vm.busy) {
                item { ChatEmptyState(vm.modelFor(convo).label) }
            }
            if (vm.dropped > 0) {
                item { ContextNotice(vm.dropped) }
            }

            items(convo.messages) { msg ->
                RisesIn(key = msg.at) {
                    Column {
                        Bubble(msg)
                        if (!msg.reasoning.isNullOrBlank()) {
                            ReasoningTrace(msg.reasoning, msg.thoughtSeconds)
                        }
                    }
                }
            }

            if (vm.busy) {
                item {
                    val live = vm.live
                    when {
                        live.stillThinking -> ThinkingCard(live.reasoning, vm.turnStartedAt)
                        live.answer.isNotEmpty() ->
                            Bubble(Message(Reply.clean(live.answer), fromUser = false))

                        else -> TypingDots()
                    }
                }
            }

            // Offered only when there is something to replace, and nothing running.
            if (!vm.busy && convo.messages.lastOrNull()?.fromUser == false) {
                item {
                    QuietAction("Regenerate") {
                        buzz()
                        vm.regenerate()
                    }
                }
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

        if (vm.modelFor(convo).reasoning) {
            ThinkingSwitch(
                on = vm.thinkingEnabled,
                onChange = {
                    buzz()
                    vm.setThinking(it)
                }
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = vm.busy,
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

@Composable
private fun ChatEmptyState(modelLabel: String) {
    Column(Modifier.padding(top = 40.dp, bottom = 24.dp)) {
        Wordmark(size = 44)
        Spacer(Modifier.height(10.dp))
        Text(
            "Running $modelLabel on this phone. Nothing you type leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(msg: Message) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val buzz = tap()

    val bg = when {
        msg.isError -> Color(0xFF2A1418)
        msg.fromUser -> scheme.primary
        else -> scheme.surfaceVariant
    }
    val fg = when {
        msg.isError -> Color(0xFFFF9BA6)
        msg.fromUser -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 330.dp)
                .clip(
                    if (msg.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                    else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                )
                .background(bg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        buzz()
                        copyToClipboard(context, msg.text)
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // What the user typed is literal; only replies are marked up.
            if (msg.fromUser || msg.isError) {
                Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = fg)
            } else {
                MarkdownText(msg.text, fg)
            }
        }
    }
}

@Composable
private fun ContextNotice(dropped: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            "$dropped earlier message${if (dropped == 1) "" else "s"} " +
                "no longer fit in the model's memory",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("maik", text))
    // Android 13+ shows its own copy confirmation; a second one would be noise.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 160, easing = LinearEasing),
                    RepeatMode.Reverse
                ),
                label = "d$i"
            )
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(a)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val enabled = !busy
    val canSend = value.isNotBlank() && enabled

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    if (busy) "maik is answering…" else "Ask maik anything…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val source = rememberPressSource()
        val bg by animateColorAsState(
            if (canSend || busy) scheme.primary else scheme.surfaceVariant,
            tween(Motion.NORMAL),
            label = "sendBg"
        )
        Box(
            Modifier
                .size(50.dp)
                .pressable(source)
                .clip(CircleShape)
                .background(bg)
                .clickable(
                    enabled = canSend || busy,
                    interactionSource = source,
                    indication = null,
                    onClick = if (busy) onStop else onSend
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = busy,
                transitionSpec = { scaleIn(tween(Motion.QUICK)) togetherWith scaleOut(tween(Motion.QUICK)) },
                label = "sendGlyph"
            ) { running ->
                if (running) {
                    StopSquare(scheme.onPrimary)
                } else {
                    ArrowUp(
                        if (canSend) scheme.onPrimary
                        else scheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
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
                            else scheme.onSurfaceVariant.copy(alpha = 0.35f)
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

/**
 * Whether this model should reason before answering. It lives here rather than in
 * Settings because it changes what the next reply will be like, and that decision
 * belongs next to the thing you are about to send.
 */
@Composable
private fun ThinkingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .pressable(source)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null) { onChange(!on) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(
                    if (on) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (on) "Thinks before answering" else "Answers straight away",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (on) "Turn off" else "Turn on",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary
        )
    }
}

/* ================= shared ================= */

@Composable
fun Wordmark(size: Int = 26) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("maik") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
        },
        fontFamily = HkGrotesk,
        fontWeight = FontWeight(800),
        fontSize = size.sp,
        letterSpacing = (-size * 0.045).sp
    )
}

@Composable
fun OnDevicePill() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "a"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(a)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "ON-DEVICE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { ChevronLeft(MaterialTheme.colorScheme.onBackground) }
        } else {
            Spacer(Modifier.width(8.dp))
        }

        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )

        trailing?.invoke()
        Spacer(Modifier.width(8.dp))
    }
    HorizontalLine()
}

@Composable
fun HorizontalLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    )
}

@Composable
fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val buzz = tap()
    val source = rememberPressSource()
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clip(CircleShape)
            .background(if (enabled) scheme.primary else scheme.surfaceVariant)
            .clickable(enabled = enabled, interactionSource = source, indication = null) {
                buzz()
                onClick()
            }
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

/** A low-key text action, centred — used where a button would shout. */
@Composable
fun QuietAction(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
                .clip(CircleShape)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    CircleShape
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

@Composable
fun OutlineButton(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberPressSource()
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(source)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
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
fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = rememberPressSource()
    Box(
        Modifier
            .size(40.dp)
            .pressable(source)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun DialogTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun EditorField(value: String, singleLine: Boolean = false, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
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
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 10,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ---- hand-drawn glyphs, so no icon dependency is needed ---- */

@Composable
fun ArrowUp(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val s = w * 0.12f
        drawLine(tint, Offset(w / 2, w * 0.88f), Offset(w / 2, w * 0.12f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.16f, w * 0.46f), Offset(w / 2, w * 0.12f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.84f, w * 0.46f), Offset(w / 2, w * 0.12f), s, StrokeCap.Round)
    }
}

@Composable
fun StopSquare(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, w * 0.14f),
            size = Size(w * 0.72f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.16f)
        )
    }
}

@Composable
fun ChevronLeft(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val s = w * 0.13f
        drawLine(tint, Offset(w * 0.66f, w * 0.1f), Offset(w * 0.3f, w * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.66f, w * 0.9f), s, StrokeCap.Round)
    }
}

@Composable
fun Plus(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val s = w * 0.13f
        drawLine(tint, Offset(w / 2, w * 0.12f), Offset(w / 2, w * 0.88f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.12f, w / 2), Offset(w * 0.88f, w / 2), s, StrokeCap.Round)
    }
}

@Composable
fun Sliders(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val s = w * 0.13f
        listOf(0.24f, 0.5f, 0.76f).forEachIndexed { i, y ->
            drawLine(tint, Offset(w * 0.1f, w * y), Offset(w * 0.9f, w * y), s, StrokeCap.Round)
            val knob = if (i % 2 == 0) 0.68f else 0.34f
            drawCircle(tint, radius = w * 0.11f, center = Offset(w * knob, w * y))
        }
    }
}
