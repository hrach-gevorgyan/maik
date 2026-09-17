package com.maik.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import kotlinx.coroutines.delay

/**
 * Motion, gathered in one place so the app moves consistently.
 *
 * The rule throughout: movement explains where something came from. Nothing
 * animates purely for decoration, and nothing outlasts the user's patience —
 * durations are short enough that a fast tap never waits on an animation.
 */
object Motion {
    const val QUICK = 110
    const val NORMAL = 190

    /** For anything that should feel physical rather than timed. */
    fun <T> springy() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/** Going deeper slides in from the right; coming back slides out to the right. */
fun forward(): ContentTransform =
    (slideInHorizontally(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { it / 6 } +
        fadeIn(tween(Motion.NORMAL))) togetherWith
        (slideOutHorizontally(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { -it / 8 } +
            fadeOut(tween(Motion.QUICK)))

fun backward(): ContentTransform =
    (slideInHorizontally(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { -it / 8 } +
        fadeIn(tween(Motion.NORMAL))) togetherWith
        (slideOutHorizontally(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { it / 6 } +
            fadeOut(tween(Motion.QUICK)))

/**
 * Shrinks slightly while held. Applied to every tappable surface, it is the single
 * cheapest thing that makes an interface feel responsive rather than inert.
 */
fun Modifier.pressable(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(Motion.QUICK),
        label = "press"
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
  * A short lift into place. Deliberately restrained: an entrance you notice twice
  * is an entrance that is too slow, and a list that assembles itself piece by piece
  * reads as the app struggling rather than as polish.
  */
@Composable
fun RisesIn(
    key: Any?,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Motion.NORMAL)) +
            slideInVertically(tween(Motion.NORMAL, easing = FastOutSlowInEasing)) { it / 12 }
    ) { content() }
}

/**
  * Whether the app may buzz. Read by [tap] so a single setting reaches every
  * button without each one having to be handed the view model.
  */
val LocalHaptics = staticCompositionLocalOf { true }

/**
 * A tap buzz, silent when the user has turned haptics off. It drives the vibrator
 * directly: the view-based haptic that Compose uses is muted or barely felt on many
 * phones, Samsung's included.
 */
@Composable
fun tap(): () -> Unit = buzzer(strong = true)

/** A lighter tick, for things that happen rather than things you pressed. */
@Composable
fun tick(): () -> Unit = buzzer(strong = false)

@Composable
private fun buzzer(strong: Boolean): () -> Unit {
    val enabled = LocalHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vibrator = remember(context) { vibratorOf(context) }
    return remember(enabled, vibrator, strong) {
        {
            if (enabled && vibrator?.hasVibrator() == true) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        vibrator.vibrate(
                            android.os.VibrationEffect.createPredefined(
                                if (strong) android.os.VibrationEffect.EFFECT_CLICK
                                else android.os.VibrationEffect.EFFECT_TICK
                            )
                        )
                    } else {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(if (strong) 20L else 10L, 120))
                    }
                }
            }
        }
    }
}

private fun vibratorOf(context: android.content.Context): android.os.Vibrator? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

/** Convenience for the many places that need their own interaction source. */
@Composable
fun rememberPressSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }
