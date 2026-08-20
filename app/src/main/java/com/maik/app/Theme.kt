package com.maik.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

val HkGrotesk = FontFamily(
    hk(300), hk(400), hk(500), hk(600), hk(700), hk(800)
)

// A deliberately narrow palette: near-black, bone, one acid accent.
val Ink = Color(0xFF08080B)
val Surface1 = Color(0xFF121218)
val Surface2 = Color(0xFF1B1B23)
val Bone = Color(0xFFEDEDF2)
val Muted = Color(0xFF83838F)
val Acid = Color(0xFFD8FF3E)
val Line = Color(0xFF24242E)

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
)

private val Light = lightColorScheme(
    primary = Color(0xFF1B1B23),
    onPrimary = Color(0xFFF7F7FA),
    background = Color(0xFFF7F7FA),
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Ink,
    outline = Color(0xFFDCDCE4),
)

private fun style(size: Int, weight: Int, lineHeight: Int, tracking: Double) = TextStyle(
    fontFamily = HkGrotesk,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp
)

private val MaikType = Typography(
    displayLarge = style(56, 800, 56, (-2.0)),
    headlineSmall = style(22, 700, 28, (-0.6)),
    titleMedium = style(16, 600, 22, (-0.2)),
    bodyLarge = style(16, 400, 24, (-0.1)),
    bodyMedium = style(15, 400, 22, (-0.1)),
    labelLarge = style(14, 600, 18, 0.0),
    labelSmall = style(11, 600, 14, 1.2),
)

@Composable
fun MaikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = MaikType,
        content = content
    )
}
