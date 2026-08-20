package com.maik.app

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** HK Grotesk, shipped as a single variable font and instanced per weight. */
@OptIn(ExperimentalTextApi::class)
private fun hk(weight: Int) = Font(
    resId = R.font.hk_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val HkGrotesk = FontFamily(hk(300), hk(400), hk(500), hk(600), hk(700), hk(800))

// A deliberately narrow palette: near-black, bone, one acid accent.
private val Ink = Color(0xFF08080B)
private val Surface1 = Color(0xFF121218)
private val Surface2 = Color(0xFF1B1B23)
private val Bone = Color(0xFFEDEDF2)
private val Line = Color(0xFF24242E)

// The same idea inverted: paper, charcoal, and an accent darkened enough to carry
// white text — the acid green is unreadable under black at small sizes.
private val Paper = Color(0xFFF6F6F8)
private val PaperSurface = Color(0xFFFFFFFF)
private val PaperSurfaceAlt = Color(0xFFEDEDF1)
private val Charcoal = Color(0xFF14141A)
private val PaperLine = Color(0xFFDDDDE4)

private val Acid = Color(0xFFD8FF3E)
private val AcidDeep = Color(0xFF4C5F00)

/** Error tint, chosen per scheme so it stays legible on either ground. */
private val DangerDark = Color(0xFFFF9BA6)
private val DangerLight = Color(0xFFB3261E)

private val Dark = darkColorScheme(
    primary = Acid,
    onPrimary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Surface1,
    onSurface = Bone,
    surfaceVariant = Surface2,
    onSurfaceVariant = Bone,
    outline = Line,
    error = DangerDark
)

private val Light = lightColorScheme(
    primary = AcidDeep,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Charcoal,
    surface = PaperSurface,
    onSurface = Charcoal,
    surfaceVariant = PaperSurfaceAlt,
    onSurfaceVariant = Charcoal,
    outline = PaperLine,
    error = DangerLight
)

private fun style(size: Int, weight: Int, lineHeight: Int, tracking: Double) = TextStyle(
    fontFamily = HkGrotesk,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp
)

private val MaikType = Typography(
    displayLarge = style(56, 800, 56, -2.0),
    headlineSmall = style(22, 700, 28, -0.6),
    titleMedium = style(16, 600, 22, -0.2),
    bodyLarge = style(16, 400, 24, -0.1),
    bodyMedium = style(15, 400, 22, -0.1),
    labelLarge = style(14, 600, 18, 0.0),
    labelSmall = style(11, 600, 14, 1.2)
)

@Composable
fun MaikTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val target = if (dark) Dark else Light

    // The system draws the clock and battery over our background, so it has to be
    // told which way round we are — otherwise light theme gets white-on-white.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    // Crossfade every colour rather than snapping, so switching theme reads as one
    // continuous movement instead of a flash.
    val spec = tween<Color>(420)
    val scheme = target.copy(
        primary = animateColorAsState(target.primary, spec, label = "primary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "onPrimary").value,
        background = animateColorAsState(target.background, spec, label = "bg").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "onBg").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, spec, label = "sv").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "osv").value,
        outline = animateColorAsState(target.outline, spec, label = "outline").value,
        error = animateColorAsState(target.error, spec, label = "error").value
    )

    MaterialTheme(colorScheme = scheme, typography = MaikType, content = content)
}
