package com.ultron.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.ultron.ai.ui.theme.UltronColors

/**
 * Rotating light that runs around the screen edge while a live session is really active.
 * It is drawn only when [active] is true (never as decoration), and is static when reduced motion is on.
 */
@Composable
fun LiveEdgeLight(active: Boolean, intense: Boolean, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val t = rememberInfiniteTransition(label = "edge")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (intense) 2200 else 4200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val deg = if (reducedMotion) 0f else angle
    val c1 = UltronColors.Cyan; val c2 = UltronColors.Violet; val c3 = UltronColors.Blue
    Canvas(modifier.fillMaxSize()) {
        val w = if (intense) 7.dp.toPx() else 4.dp.toPx()
        val corner = 36.dp.toPx()
        val brush = Brush.sweepGradient(listOf(Color.Transparent, c1, c2, c3, Color.Transparent, Color.Transparent), center = center)
        rotate(deg, pivot = center) {
            // Wide soft glow under a crisp line.
            drawRoundRect(brush, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(corner), style = Stroke(w * 3f), alpha = 0.22f)
            drawRoundRect(brush, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(corner), style = Stroke(w), alpha = 0.95f)
        }
    }
}
