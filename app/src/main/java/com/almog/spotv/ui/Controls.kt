package com.almog.spotv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almog.spotv.Constants

/**
 * A round remote-control button. Focus (D-pad) makes it bigger and white, so it reads from the sofa;
 * OK / Enter on the remote clicks it.
 */
@Composable
fun TvButton(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    icon: DrawScope.(Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.18f else 1f, label = "buttonScale")
    val base = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier
    Box(
        modifier = base
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.46f)) { icon(if (focused) Color.Black else Color.White) }
    }
}

/**
 * The progress bar. Focus it (D-pad up from the buttons), then left / right jump 10 seconds;
 * OK / Enter does [onActivate] (play / pause).
 */
@Composable
fun SeekBar(
    fraction: Float,
    accent: Color,
    onSeekBy: (Long) -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    thin: Dp = 6.dp,
    thick: Dp = 12.dp,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val height by animateDpAsState(if (focused) thick else thin, label = "seekHeight")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thick)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> { onSeekBy(-Constants.SEEK_STEP_MS); true }
                        Key.DirectionRight -> { onSeekBy(Constants.SEEK_STEP_MS); true }
                        Key.DirectionCenter, Key.Enter -> { onActivate(); true }
                        else -> false
                    }
                }
            }
            .focusable(interactionSource = source),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) Color.White else accent)
            )
        }
    }
}
