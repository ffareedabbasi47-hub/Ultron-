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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.ultron.ai.ui.theme.UltronColors

/**
 * Light that runs around the screen border while a live session is really active: a faint ring plus two bright
 * "comets" with fading tails travelling at constant speed along the rounded-rectangle outline.
 * Drawn only when [active]; static (no motion) when reduced motion is on.
 */
@Composable
fun LiveEdgeLight(active: Boolean, intense: Boolean, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val t = rememberInfiniteTransition(label = "edge")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (intense) 1800 else 3200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val progress = if (reducedMotion) 0.12f else p
    val c1 = UltronColors.Cyan; val c2 = UltronColors.Violet
    val measure = remember { PathMeasure() }
    val outline = remember { Path() }
    val seg = remember { Path() }
    Canvas(modifier.fillMaxSize()) {
        val w = (if (intense) 5f else 3.5f).dp.toPx()
        val inset = w
        outline.rewind()
        outline.addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(34.dp.toPx())))
        measure.setPath(outline, true)
        val len = measure.length
        drawPath(outline, c1.copy(alpha = 0.16f), style = Stroke(w))
        val tail = len * 0.26f
        val steps = 30
        for (offset in floatArrayOf(0f, 0.5f)) {
            val head = ((progress + offset) % 1f) * len
            for (i in 0 until steps) {
                val a = head - tail * (i + 1) / steps
                val b = head - tail * i / steps
                val fade = 1f - i / steps.toFloat()
                val color = lerp(c1, c2, i / steps.toFloat()).copy(alpha = fade * fade)
                seg.rewind()
                fun add(from: Float, to: Float) { measure.getSegment(from, to, seg, true) }
                if (a >= 0f) add(a, b)
                else if (b <= 0f) add(a + len, b + len)
                else { add(a + len, len); add(0f, b) }
                drawPath(seg, color.copy(alpha = color.alpha * 0.35f), style = Stroke(w * 3.2f, cap = StrokeCap.Round)) // soft glow
                drawPath(seg, color, style = Stroke(w * (0.55f + 0.45f * fade), cap = StrokeCap.Round))
            }
        }
    }
}
