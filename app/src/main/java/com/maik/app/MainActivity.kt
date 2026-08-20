package com.maik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaikTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { ChatScreen() }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.busy) {
        val target = vm.messages.size // busy row sits one past the last message
        if (target > 0) listState.animateScrollToItem(target)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Header(
            canClear = vm.messages.isNotEmpty() && !vm.busy && vm.stage is Stage.Ready,
            onClear = vm::clear
        )

        Box(Modifier.weight(1f)) {
            when (val s = vm.stage) {
                is Stage.Ready ->
                    if (vm.messages.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(vm.messages) { _, msg -> Bubble(msg) }
                            if (vm.busy) item { TypingDots() }
                        }
                    }

                else -> SetupScreen(
                    stage = s,
                    spec = vm.spec,
                    onDownload = vm::startDownload,
                    onRetry = vm::retry
                )
            }
        }

        if (vm.stage is Stage.Ready) {
            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = !vm.busy,
                onSend = {
                    vm.send(input)
                    input = ""
                }
            )
        }
    }
}

/* ---------- wordmark ---------- */

@Composable
private fun Wordmark(size: Int = 26) {
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
private fun Header(canClear: Boolean, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        Spacer(Modifier.width(12.dp))
        OnDevicePill()
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(canClear, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "clear",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
}

@Composable
private fun OnDevicePill() {
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
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                CircleShape
            )
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

/* ---------- content ---------- */

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 64)
        Spacer(Modifier.height(14.dp))
        Text(
            "A small model that never leaves your phone.\nNo account. No network. No trace.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

/* ---------- first-run setup ---------- */

private fun mb(bytes: Long) = "%,d".format(bytes / 1024 / 1024)

@Composable
private fun SetupScreen(
    stage: Stage,
    spec: ModelSpec,
    onDownload: () -> Unit,
    onRetry: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 56)
        Spacer(Modifier.height(18.dp))

        when (stage) {
            is Stage.NeedsModel -> {
                Text(
                    "maik carries its own brain. Fetch it once, then it works " +
                        "forever with the network off.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(28.dp))
                SpecRow("MODEL", spec.label)
                SpecRow("SIZE", "${mb(spec.approxBytes)} MB, one time")
                SpecRow("AFTER", "Fully offline")
                Spacer(Modifier.height(32.dp))
                BigButton("Download model", onDownload)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Use Wi-Fi. Nothing you type is ever uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }

            is Stage.Downloading -> {
                val pct = (stage.fraction * 100).toInt()
                Text(
                    "Downloading",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(20.dp))
                ProgressBar(stage.fraction)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${mb(stage.bytes)} / ${mb(stage.total)} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.primary
                    )
                }
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
                    stage.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(28.dp))
                BigButton("Try again", onRetry)
            }

            is Stage.Ready -> Unit
        }
    }
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
private fun BigButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/* ---------- chat ---------- */

@Composable
private fun Bubble(msg: Message) {
    val userShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val botShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val scheme = MaterialTheme.colorScheme

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
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(if (msg.fromUser) userShape else botShape)
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun TypingDots() {
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

/* ---------- composer ---------- */

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
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
                    "Ask maik anything…",
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth()
            )
        }

        SendButton(enabled = canSend, onClick = onSend)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (enabled) scheme.primary else scheme.surfaceVariant,
        label = "sendBg"
    )
    val fg = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.35f)

    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
            // shaft
            drawLine(fg, Offset(w / 2, w * 0.88f), Offset(w / 2, w * 0.12f), stroke.width, StrokeCap.Round)
            // head
            drawLine(fg, Offset(w * 0.16f, w * 0.46f), Offset(w / 2, w * 0.12f), stroke.width, StrokeCap.Round)
            drawLine(fg, Offset(w * 0.84f, w * 0.46f), Offset(w / 2, w * 0.12f), stroke.width, StrokeCap.Round)
        }
    }
}
