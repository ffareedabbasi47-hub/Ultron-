package com.ultron.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.ultron.ai.AssistantState
import com.ultron.ai.ui.theme.LocalUiPrefs
import com.ultron.ai.ui.theme.UltronColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Horizontal equalizer under the ULTRON wordmark. Amplitude follows the real assistant state:
 * the real mic level while listening; an approximate animation while speaking/thinking
 * (Android TTS exposes no audio amplitude); near-flat when idle.
 */
@Composable
fun Equalizer(state: AssistantState, level: Float, modifier: Modifier = Modifier) {
    val prefs = LocalUiPrefs.current
    val t = rememberInfiniteTransition(label = "eq")
    val phase = t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "eqp")
    val amp = when (state) {
        AssistantState.LISTENING -> 0.25f + level * 0.75f
        AssistantState.SPEAKING -> 0.6f
        AssistantState.THINKING -> 0.35f
        AssistantState.IDLE -> 0.12f
        else -> 0.05f
    }
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val n = if (prefs.lowPerformance) 24 else 44
        val gap = size.width / n
        val maxH = size.height
        for (i in 0 until n) {
            val x = gap * (i + 0.5f)
            val edge = 1f - abs((i - n / 2f) / (n / 2f)) * 0.75f // taller in the middle
            val wob = if (prefs.animate) (sin(phase.value + i * 0.6f) * 0.5f + 0.5f) else 0.5f
            val h = (maxH * 0.14f + maxH * 0.86f * amp * wob * edge).coerceAtMost(maxH)
            val color = lerp(UltronColors.Cyan, UltronColors.Magenta, i / (n - 1f)).copy(alpha = 0.55f + 0.4f * edge)
            drawLine(color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = gap * 0.45f, cap = StrokeCap.Round)
        }
    }
}

@Suppress("unused")
private val keepColor = Color.Unspecified
