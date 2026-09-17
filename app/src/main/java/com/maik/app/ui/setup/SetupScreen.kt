package com.maik.app.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maik.app.*
import com.maik.app.R
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

    // Whatever starts a download — first fetch, continue, download again — waits here
    // until the mobile-data warning and the notification explainer have had their say.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun startNow() {
        pending?.invoke()
        pending = null
    }

    // Android's own permission box says nothing about why. Explain first, in our
    // words, then ask — and only at the moment a download is actually starting.
    fun begin() {
        if (notifications == null) startNow() else explainNotifications = true
    }

    fun guardedStart(action: () -> Unit) {
        pending = action
        if (vm.onMeteredNetwork()) warnMetered = true else begin()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Wordmark(size = 52)
        Spacer(Modifier.height(18.dp))

        // Keyed by the kind of stage, so download progress ticking along doesn't
        // restart the transition; only moving from one state to the next does.
        AnimatedContent(
            targetState = vm.stage,
            contentKey = { stage -> stage::class to (stage as? Stage.Downloading)?.verifying },
            transitionSpec = {
                (fadeIn(tween(Motion.NORMAL, delayMillis = 60)) +
                    slideInVertically(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { it / 12 }) togetherWith
                    fadeOut(tween(Motion.QUICK)) using
                    SizeTransform(clip = false)
            },
            label = "setupStage"
        ) { stage ->
        when (val s = stage) {
            is Stage.NeedsModel -> RisesIn(key = "needs") {
                Column {
                    Text(
                        stringResource(R.string.setup_maik_carries_its_own_brain),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(22.dp))
                    SpecRow(stringResource(R.string.setup_model), spec.label)
                    SpecRow(stringResource(R.string.setup_size), stringResource(R.string.setup_mb_once, spec.approxMb))
                    SpecRow(stringResource(R.string.setup_after), stringResource(R.string.setup_fully_offline))
                    Spacer(Modifier.height(26.dp))
                    BigButton(stringResource(R.string.setup_download, spec.label)) {
                        guardedStart(vm::startDownload)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.setup_wi_fi_recommended_nothing_you),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                    Spacer(Modifier.height(20.dp))
                    QuietAction(stringResource(R.string.setup_choose_a_different_model), vm::openModels)
                }
            }

            is Stage.Downloading -> {
                val started = s.bytes > 0
                Text(
                    when {
                        s.verifying -> stringResource(R.string.setup_verifying_title)
                        started -> stringResource(R.string.setup_downloading, spec.label)
                        else -> stringResource(R.string.setup_starting)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(20.dp))
                ProgressBar(s.fraction, indeterminate = !started || s.verifying)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            s.verifying -> stringResource(R.string.setup_verifying_detail)
                            started -> stringResource(R.string.setup_mb, s.bytes / 1024 / 1024, s.total / 1024 / 1024)
                            else -> stringResource(R.string.setup_connecting)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                    Spacer(Modifier.weight(1f))
                    if (started && !s.verifying) {
                        Text(
                            java.text.NumberFormat.getPercentInstance().format(s.fraction.toDouble()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.setup_keeps_going_if_you_lock),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
                Spacer(Modifier.height(22.dp))
                OutlineButton(stringResource(R.string.setup_cancel), onClick = vm::cancelDownload)
            }

            is Stage.Loading -> {
                Text(
                    stringResource(R.string.setup_warming_up, spec.label),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.setup_first_load_takes_a_moment),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
                Spacer(Modifier.height(20.dp))
                TypingDots()
            }

            is Stage.Broken -> RisesIn(key = s.summary) {
                BrokenState(
                    vm, s,
                    onRefetch = { guardedStart(vm::startDownload) },
                    onRedownload = { guardedStart(vm::retry) }
                )
            }

            is Stage.Ready -> RisesIn(key = "ready") {
                Column {
                    Text(
                        stringResource(R.string.setup_is_ready, spec.label),
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.setup_it_lives_on_this_phone),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.64f)
                    )
                    Spacer(Modifier.height(26.dp))
                    BigButton(stringResource(R.string.setup_start_chatting), onClick = vm::acknowledgeInstall)
                }
            }
        }

        }

        if (vm.stage !is Stage.Ready) {
            Spacer(Modifier.height(24.dp))
            QuietAction(if (vm.conversations.isEmpty()) stringResource(R.string.setup_not_now) else stringResource(R.string.setup_back), vm::back)
        }
    }

    }

    if (explainNotifications) {
        AlertDialog(
            onDismissRequest = {
                explainNotifications = false
                pending = null
            },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle(stringResource(R.string.setup_show_download_progress)) },
            text = {
                Text(
                    stringResource(R.string.setup_mb_takes_a_while_a, spec.approxMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    notifications?.invoke()
                    startNow()
                }) { Text(stringResource(R.string.setup_show_progress), color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    startNow()
                }) { Text(stringResource(R.string.setup_not_now), color = scheme.onSurfaceVariant) }
            }
        )
    }

    if (warnMetered) {
        AlertDialog(
            onDismissRequest = {
                warnMetered = false
                pending = null
            },
            containerColor = scheme.surfaceVariant,
            title = { DialogTitle(stringResource(R.string.setup_you_are_not_on_wi)) },
            text = {
                Text(
                    stringResource(R.string.setup_this_will_pull_mb_over, spec.approxMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    warnMetered = false
                    begin()
                }) { Text(stringResource(R.string.setup_download_anyway), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    warnMetered = false
                    pending = null
                }) {
                    Text(stringResource(R.string.setup_wait_for_wi_fi), color = scheme.primary)
                }
            }
        )
    }
}

@Composable
private fun BrokenState(vm: ChatViewModel, stage: Stage.Broken, onRefetch: () -> Unit, onRedownload: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showDetail by remember { mutableStateOf(false) }

    Column {
        Text(
            stringResource(R.string.setup_that_didn_t_work),
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
                if (showDetail) stringResource(R.string.setup_hide_details) else stringResource(R.string.setup_show_details),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showDetail = !showDetail }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
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
            Fix.RESUME_DOWNLOAD -> BigButton(stringResource(R.string.setup_continue_download), onClick = onRefetch)
            Fix.REDOWNLOAD -> BigButton(stringResource(R.string.setup_download_again), onClick = onRedownload)
            Fix.RETRY_LOAD -> BigButton(stringResource(R.string.setup_try_again), onClick = vm::retry)
        }
        Spacer(Modifier.height(12.dp))
        QuietAction(stringResource(R.string.setup_try_a_different_model), vm::openModels)
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
    val animated by animateFloatAsState(fraction, tween(Motion.NORMAL), label = "dl")

    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (indeterminate) Modifier.progressSemantics()
                else Modifier.progressSemantics(fraction.coerceIn(0f, 1f))
            )
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
