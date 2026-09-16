package com.maik.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.maik.app.*
import com.maik.app.data.*
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*

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
