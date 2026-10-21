package com.ultron.ai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ultron.ai.data.settings.ThemeVariant

object UltronColors {
    val Bg = Color(0xFF07060D)
    val BgDeep = Color(0xFF110B22)
    val Graphite = Color(0xFF17151F)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Magenta = Color(0xFFD946EF)
    val Red = Color(0xFFFF2A44)
    val Gold = Color(0xFFFBBF24)
    val Green = Color(0xFF34D399)
    val TextHi = Color(0xFFF4F1FF)
    val TextLo = Color(0xFFA39CBF)
    val GlassTop = Color(0x2AFFFFFF)
    val GlassBottom = Color(0x0DFFFFFF)
    val GlassBorder = Color(0x38FFFFFF)
}

/** Motion/performance preferences, available to every composable. */
data class UiPrefs(val reducedMotion: Boolean = false, val lowPerformance: Boolean = false) {
    val animate: Boolean get() = !reducedMotion
    val rich: Boolean get() = !lowPerformance && !reducedMotion
}

val LocalUiPrefs = compositionLocalOf { UiPrefs() }

private val scheme = darkColorScheme(
    primary = UltronColors.Violet,
    onPrimary = Color.White,
    secondary = UltronColors.Cyan,
    onSecondary = Color.Black,
    tertiary = UltronColors.Magenta,
    background = UltronColors.Bg,
    onBackground = UltronColors.TextHi,
    surface = UltronColors.Graphite,
    onSurface = UltronColors.TextHi,
    surfaceVariant = Color(0xFF241F33),
    onSurfaceVariant = UltronColors.TextLo,
    error = UltronColors.Red,
    outline = UltronColors.GlassBorder,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 4.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp),
)

@Composable
fun UltronTheme(
    variant: ThemeVariant = ThemeVariant.PURPLE,
    prefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) {
    // Brand is dark-only by design; isSystemInDarkTheme intentionally not consulted.
    @Suppress("UNUSED_VARIABLE") val ignored = isSystemInDarkTheme()
    CompositionLocalProvider(LocalUiPrefs provides prefs, LocalThemeVariant provides variant) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            UltronBackground(variant) { content() }
        }
    }
}

val LocalThemeVariant = compositionLocalOf { ThemeVariant.PURPLE }

@Composable
fun UltronBackground(variant: ThemeVariant, content: @Composable () -> Unit) {
    val modifier = if (variant == ThemeVariant.AMOLED) {
        Modifier.fillMaxSize().background(Color.Black)
    } else {
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(UltronColors.BgDeep, UltronColors.Bg)))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(UltronColors.Violet.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.12f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.85f, size.height * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(UltronColors.Magenta.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.8f),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.1f, size.height * 0.8f),
                )
            }
    }
    Box(modifier) { content() }
}
