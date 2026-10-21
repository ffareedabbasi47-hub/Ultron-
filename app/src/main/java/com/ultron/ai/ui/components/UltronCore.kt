package com.ultron.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultron.ai.AssistantState
import com.ultron.ai.ui.theme.LocalUiPrefs
import com.ultron.ai.ui.theme.UltronColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class CorePalette(val primary: Color, val secondary: Color, val label: String)

private fun palette(state: AssistantState) = when (state) {
    AssistantState.IDLE -> CorePalette(UltronColors.Violet, UltronColors.Cyan, "Idle")
    AssistantState.LISTENING -> CorePalette(UltronColors.Cyan, UltronColors.Violet, "Listening")
    AssistantState.THINKING -> CorePalette(UltronColors.Gold, UltronColors.Magenta, "Thinking")
    AssistantState.SPEAKING -> CorePalette(UltronColors.Red, UltronColors.Magenta, "Speaking")
    AssistantState.ERROR -> CorePalette(UltronColors.Red, UltronColors.Gold, "Error")
    AssistantState.OFFLINE -> CorePalette(Color(0xFF6B6785), Color(0xFF45415C), "Offline")
}

/**
 * The ULTRON core: layered rings, arcs, a radial waveform and (in rich mode) particles.
 * [level] is a real 0..1 microphone level while listening. While speaking, Android TTS exposes
 * no amplitude, so the waveform is an approximate animation, not a measurement.
 * [compact] hides the radial waveform and central optic so text can be overlaid in the middle
 * (Home screen). Honors reduced-motion (static) and low-performance (fewer layers) preferences.
 */
@Composable
fun UltronCore(state: AssistantState, level: Float, modifier: Modifier = Modifier, compact: Boolean = false) {
    val prefs = LocalUiPrefs.current
    val p = palette(state)
    val transition = rememberInfiniteTransition(label = "core")
    val rotation = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "rot")
    val phase = transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val pulse = transition.animateFloat(0.94f, 1.0f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    val particles = remember { List(16) { i -> Triple((i * 137.5f) % 360f, 0.35f + (i * 53 % 60) / 100f, 0.2f + (i % 5) * 0.12f) } }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "ULTRON core, state: ${p.label}" },
    ) {
        val animate = prefs.animate
        val rot = if (animate) rotation.value else 0f
        val ph = if (animate) phase.value else 0f
        val pl = if (animate) pulse.value else 1f
        val c = center
        val r = size.minDimension / 2f * 0.96f

        // ambient glow
        drawCircle(
            Brush.radialGradient(listOf(p.primary.copy(alpha = 0.35f * pl), Color.Transparent), center = c, radius = r),
            radius = r, center = c,
        )

        // outer ring + ticks
        drawCircle(p.primary.copy(alpha = 0.55f), radius = r * 0.98f, center = c, style = Stroke(2.dp.toPx()))
        if (!prefs.lowPerformance) {
            val ticks = 72
            for (i in 0 until ticks) {
                val a = Math.toRadians((i * 360.0 / ticks) + rot * 0.3)
                val long = i % 6 == 0
                val r1 = r * (if (long) 0.90f else 0.93f)
                val r2 = r * 0.96f
                drawLine(
                    p.secondary.copy(alpha = if (long) 0.8f else 0.35f),
                    Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                    Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2),
                    strokeWidth = if (long) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
        }

        // rotating arc segments
        arcs(c, r * 0.82f, rot, p.secondary, count = 3, sweep = 62f, width = 3.dp.toPx())
        arcs(c, r * 0.70f, -rot * 1.4f, p.primary, count = 2, sweep = 95f, width = 4.dp.toPx())
        drawCircle(p.primary.copy(alpha = 0.25f), radius = r * 0.60f, center = c, style = Stroke(1.dp.toPx()))

        // radial waveform
        val bars = if (prefs.lowPerformance) 28 else 56
        val base = r * 0.40f
        val amp = when (state) {
            AssistantState.LISTENING -> 0.25f + level * 0.75f // real mic level
            AssistantState.SPEAKING -> 0.55f // approximate animation
            AssistantState.THINKING -> 0.30f
            AssistantState.IDLE -> 0.12f
            else -> 0.06f
        }
        if (!compact) for (i in 0 until bars) {
            val a = 2 * PI * i / bars
            val wobble = (sin(ph + i * 0.55) * 0.5 + 0.5).toFloat()
            val len = r * 0.16f * (0.2f + amp * wobble)
            drawLine(
                p.secondary.copy(alpha = 0.9f),
                Offset(c.x + cos(a).toFloat() * base, c.y + sin(a).toFloat() * base),
                Offset(c.x + cos(a).toFloat() * (base + len), c.y + sin(a).toFloat() * (base + len)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }

        // particles
        if (prefs.rich) {
            particles.forEach { (angle, rad, speed) ->
                val a = Math.toRadians((angle + rot * speed * 3).toDouble())
                val pr = r * rad
                drawCircle(
                    p.secondary.copy(alpha = 0.55f),
                    radius = 1.6f.dp.toPx(),
                    center = Offset(c.x + cos(a).toFloat() * pr, c.y + sin(a).toFloat() * pr),
                )
            }
        }

        // central optic
        if (compact) return@Canvas
        val core = r * 0.30f * pl
        drawCircle(Brush.radialGradient(listOf(p.primary, p.primary.copy(alpha = 0.15f)), center = c, radius = core), radius = core, center = c)
        drawCircle(Color.White.copy(alpha = 0.9f), radius = core * 0.18f, center = Offset(c.x - core * 0.12f, c.y - core * 0.12f))
    }
}

private fun DrawScope.arcs(c: Offset, radius: Float, startDeg: Float, color: Color, count: Int, sweep: Float, width: Float) {
    val topLeft = Offset(c.x - radius, c.y - radius)
    val size = Size(radius * 2, radius * 2)
    for (i in 0 until count) {
        drawArc(
            color.copy(alpha = 0.85f), startAngle = startDeg + i * (360f / count), sweepAngle = sweep,
            useCenter = false, topLeft = topLeft, size = size, style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}



fun coreStateLabel(state: AssistantState): String = palette(state).label
