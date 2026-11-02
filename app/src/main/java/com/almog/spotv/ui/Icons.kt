package com.almog.spotv.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// Control icons drawn by hand so the app needs no icon library. Each fills the DrawScope's square.

private fun DrawScope.triangle(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * x1, h * y1)
        lineTo(w * x2, h * y2)
        lineTo(w * x3, h * y3)
        close()
    }
    drawPath(path, c)
}

fun DrawScope.drawPlayIcon(c: Color) = triangle(c, 0.20f, 0.08f, 0.94f, 0.50f, 0.20f, 0.92f)

fun DrawScope.drawPauseIcon(c: Color) {
    val w = size.width
    val h = size.height
    val bar = Size(w * 0.24f, h * 0.84f)
    val r = CornerRadius(w * 0.05f)
    drawRoundRect(c, Offset(w * 0.15f, h * 0.08f), bar, r)
    drawRoundRect(c, Offset(w * 0.61f, h * 0.08f), bar, r)
}

fun DrawScope.drawNextIcon(c: Color) {
    val w = size.width
    val h = size.height
    triangle(c, 0.06f, 0.12f, 0.72f, 0.50f, 0.06f, 0.88f)
    drawRoundRect(c, Offset(w * 0.76f, h * 0.12f), Size(w * 0.16f, h * 0.76f), CornerRadius(w * 0.04f))
}

fun DrawScope.drawPreviousIcon(c: Color) {
    val w = size.width
    val h = size.height
    triangle(c, 0.94f, 0.12f, 0.28f, 0.50f, 0.94f, 0.88f)
    drawRoundRect(c, Offset(w * 0.08f, h * 0.12f), Size(w * 0.16f, h * 0.76f), CornerRadius(w * 0.04f))
}

fun DrawScope.drawShuffleIcon(c: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.09f
    drawLine(c, Offset(w * 0.06f, h * 0.76f), Offset(w * 0.72f, h * 0.26f), stroke, StrokeCap.Round)
    drawLine(c, Offset(w * 0.06f, h * 0.26f), Offset(w * 0.72f, h * 0.76f), stroke, StrokeCap.Round)
    triangle(c, 0.74f, 0.10f, 0.98f, 0.26f, 0.74f, 0.42f)
    triangle(c, 0.74f, 0.60f, 0.98f, 0.76f, 0.74f, 0.92f)
}

fun DrawScope.drawRepeatIcon(c: Color, one: Boolean) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        c,
        Offset(w * 0.08f, h * 0.24f),
        Size(w * 0.84f, h * 0.52f),
        CornerRadius(w * 0.18f),
        Stroke(width = w * 0.08f),
    )
    triangle(c, 0.58f, 0.06f, 0.86f, 0.24f, 0.58f, 0.42f)
    triangle(c, 0.42f, 0.58f, 0.14f, 0.76f, 0.42f, 0.94f)
    if (one) {
        drawLine(c, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), w * 0.08f, StrokeCap.Round)
        drawLine(c, Offset(w * 0.43f, h * 0.43f), Offset(w * 0.5f, h * 0.38f), w * 0.07f, StrokeCap.Round)
    }
}
