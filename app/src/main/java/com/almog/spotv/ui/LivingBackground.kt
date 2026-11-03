package com.almog.spotv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.almog.spotv.lyrics.kinetic.BeatClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slow colour blobs in the cover's colours. They drift faster and glow brighter in busy parts of the
 * song and swell on the beat. Plain gradients, so it runs the same on every TV GPU.
 */
@Composable
fun LivingBackground(
    palette: List<Color>,
    beat: BeatClock,
    positionMs: () -> Long,
    pulseEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = if (palette.size >= 3) palette else (palette + palette + palette).take(3).ifEmpty { listOf(Color(0xFF1DB954)) }
    Canvas(modifier.fillMaxSize()) {
        val pos = positionMs()
        val energy = beat.energy(pos)
        val pulse = if (pulseEnabled) beat.pulse(pos) else 0f
        // The song's own clock drives the motion, so it freezes while paused and follows seeks.
        val t = pos / 1000f * (0.05f + 0.07f * energy)
        val w = size.width
        val h = size.height
        val n = 4
        for (i in 0 until n) {
            val a = t * 2f * PI.toFloat() + i * 1.7f
            val cx = w * (0.5f + 0.38f * sin(a * 0.9f + i))
            val cy = h * (0.5f + 0.34f * cos(a * 0.7f + i * 2f))
            val r = (w * (0.34f + 0.05f * i) ) * (1f + 0.06f * pulse + 0.1f * energy)
            val c = colors[i % colors.size]
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = 0.5f + 0.3f * energy + 0.15f * pulse), Color.Transparent),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
        }
    }
}
