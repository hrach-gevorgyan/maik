package com.maik.app.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.theme.*

/* ================= setup / download ================= */

@Composable
fun SetupScreen(vm: ChatViewModel) {
    val scheme = MaterialTheme.colorScheme
    val spec = vm.target
    var warnMetered by remember { mutableStateOf(false) }
    var explainNotifications by remember { mutableStateOf(false) }

    val notifications = notificationRequester()

    fun startNow() {
        vm.startDownload()
    }

    // Android's own permission box says nothing about why. Explain first, in our
    // words, then ask — and only at the moment a download is actually starting.
    fun begin() {
        if (notifications == null) startNow() else explainNotifications = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 52)
        Spacer(Modifier.height(18.dp))

        when (val s = vm.stage) {
            is Stage.NeedsModel -> RisesIn(key = "needs") {
                Column {
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
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                    Spacer(Modifier.height(20.dp))
                    QuietAction("Choose a different model", vm::openModels)
                }
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
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
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
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
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
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
                Spacer(Modifier.height(20.dp))
                TypingDots()
            }

            is Stage.Broken -> RisesIn(key = s.summary) {
                BrokenState(vm, s, onRefetch = ::begin)
            }

            is Stage.Ready -> RisesIn(key = "ready") {
                Column {
                    Text(
                        "${spec.label} is ready",
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "It lives on this phone now. You can turn the network off " +
                            "and it will keep answering.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                    Spacer(Modifier.height(26.dp))
                    BigButton("Start chatting", onClick = vm::acknowledgeInstall)
                }
            }
        }

        if (vm.stage !is Stage.Ready) {
            Spacer(Modifier.height(24.dp))
            QuietAction(if (vm.conversations.isEmpty()) "Not now" else "Back", vm::openList)
        }
    }

    if (explainNotifications) {
        AlertDialog(
            onDismissRequest = {
                explainNotifications = false
                startNow()
            },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle("Show download progress?") },
            text = {
                Text(
                    "${spec.approxMb} MB takes a while. A notification lets you watch " +
                        "it fill up and cancel it without coming back here — and it is " +
                        "the only notification maik will ever post.\n\n" +
                        "Say no and the download still runs exactly the same.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    notifications?.invoke()
                    startNow()
                }) { Text("Show progress", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    startNow()
                }) { Text("Not now", color = scheme.onSurfaceVariant) }
            }
        )
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
                }) { Text("Download anyway", color = scheme.error) }
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

    Column {
        Text(
            "That didn't work",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.error
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
                    // Engine messages run to dozens of lines of Bazel paths; the
                    // first line is the only part that ever means anything.
                    stage.detail.lineSequence().first().take(240),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        when (stage.fix) {
            Fix.RESUME_DOWNLOAD -> BigButton("Continue download", onClick = onRefetch)
            Fix.REDOWNLOAD -> BigButton("Download again", onClick = vm::retry)
            Fix.RETRY_LOAD -> BigButton("Try again", onClick = vm::retry)
        }
        Spacer(Modifier.height(12.dp))
        QuietAction("Try a different model", vm::openModels)
    }
}

/* ================= small pieces ================= */

/**
 * Returns a way to ask for notification permission, or null when there is nothing
 * to ask for — either the platform predates the permission, or it is already
 * granted. Callers use null to mean "skip the explanation entirely".
 */
@Composable
private fun notificationRequester(): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

    val context = LocalContext.current
    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    if (alreadyGranted) return null

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
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
            // A bar that moves while nothing is measurable yet, so "connecting"
            // never looks like "stuck at zero".
            val t = rememberInfiniteTransition(label = "sweep")
            val x by t.animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
                label = "x"
            )
            BoxWithConstraints {
                Box(
                    Modifier
                        .offset(x = maxWidth * x)
                        .fillMaxHeight()
                        .width(maxWidth * 0.35f)
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
