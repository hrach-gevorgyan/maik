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

    /** Switching light and dark: slow enough to read as a change of light, not a flash. */
    const val THEME = 420

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

/**
 * Arrives once: a message, a card, anything new on screen. It rises a little and
 * settles, which reads as "this just happened" without drawing attention to itself.
 */
@Composable
fun AppearsIn(content: @Composable () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            androidx.compose.animation.core.spring(
                dampingRatio = 0.78f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            )
        )
    }
    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            // A few pixels of travel, scaled to the element, not a slide across the screen.
            translationY = (1f - p) * 26f
            scaleX = 0.96f + 0.04f * p
            scaleY = 0.96f + 0.04f * p
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        }
    ) { content() }
}

/** Convenience for the many places that need their own interaction source. */
@Composable
fun rememberPressSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }
