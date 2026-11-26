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
import com.ultron.ai.data.settings.AppFont
import com.ultron.ai.data.settings.ThemeVariant
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font

/** One complete colour set. Every theme keeps the same roles so screens never change layout. */
data class Palette(
    val bg: Color, val bgDeep: Color, val surface: Color, val raised: Color,
    val primary: Color, val secondary: Color, val tertiary: Color,
    val textHi: Color, val textLo: Color,
    val glassTop: Color, val glassBottom: Color, val glassBorder: Color,
)

object Palettes {
    private val hi = Color(0xFFF4F6FF)
    private val lo = Color(0xFFAAB4D2)
    private val border = Color(0x40AFC4FF)
    fun of(v: ThemeVariant): Palette = when (v) {
        ThemeVariant.NAVY -> Palette(Color(0xFF080B1B), Color(0xFF0E1330), Color(0xFF151B3A), Color(0xFF202A55), Color(0xFF4F9CFF), Color(0xFF8B5CF6), Color(0xFF35D6E8), hi, lo, Color(0xFF232E60), Color(0xFF151B3A), border)
        ThemeVariant.PURPLE -> Palette(Color(0xFF07060D), Color(0xFF110B22), Color(0xFF1A1530), Color(0xFF2A2150), Color(0xFF9B6BFF), Color(0xFF8B5CF6), Color(0xFF22D3EE), Color(0xFFF4F1FF), Color(0xFFA39CBF), Color(0xFF2B2252), Color(0xFF1A1530), Color(0x40C4AFFF))
        ThemeVariant.CRIMSON -> Palette(Color(0xFF0D0608), Color(0xFF1C0A10), Color(0xFF2A1018), Color(0xFF3B1824), Color(0xFFFF3B52), Color(0xFFD946EF), Color(0xFFFBBF24), Color(0xFFFFF1F3), Color(0xFFCDA3AB), Color(0xFF42202C), Color(0xFF2A1018), Color(0x40FFAFBA))
        ThemeVariant.EMERALD -> Palette(Color(0xFF06100D), Color(0xFF0A1F19), Color(0xFF11302A), Color(0xFF1A443B), Color(0xFF34D399), Color(0xFF2DD4BF), Color(0xFF22D3EE), Color(0xFFEFFFF9), Color(0xFF9FC7BA), Color(0xFF1D4A40), Color(0xFF11302A), Color(0x40AFFFE0))
        ThemeVariant.GOLD -> Palette(Color(0xFF0F0B05), Color(0xFF1E1608), Color(0xFF2A200E), Color(0xFF3D2F14), Color(0xFFFBBF24), Color(0xFFF97316), Color(0xFFFDE68A), Color(0xFFFFF8E6), Color(0xFFCDBE9F), Color(0xFF47361A), Color(0xFF2A200E), Color(0x40FFE0AF))
        ThemeVariant.ICE -> Palette(Color(0xFF050B14), Color(0xFF0A1A2E), Color(0xFF0F2742), Color(0xFF183A5E), Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFF67E8F9), Color(0xFFF0F9FF), Color(0xFF9DB8D2), Color(0xFF1C4166), Color(0xFF0F2742), Color(0x40AFE0FF))
        ThemeVariant.AMOLED -> Palette(Color(0xFF000000), Color(0xFF000000), Color(0xFF0B0B12), Color(0xFF16161F), Color(0xFF4F9CFF), Color(0xFF8B5CF6), Color(0xFF35D6E8), hi, lo, Color(0xFF1A1A26), Color(0xFF0B0B12), border)
    }
}

/**
 * Colour roles used all over the UI. The values come from the selected [Palette]; reading them inside a
 * composable (or draw scope) makes it update live when the theme changes.
 */
object UltronColors {
    var current by mutableStateOf(Palettes.of(ThemeVariant.NAVY))
    val Bg get() = current.bg
    val BgDeep get() = current.bgDeep
    val Graphite get() = current.surface
    val Raised get() = current.raised
    val Blue get() = current.primary
    val Violet get() = current.secondary
    val Cyan get() = current.tertiary
    val TextHi get() = current.textHi
    val TextLo get() = current.textLo
    val GlassTop get() = current.glassTop
    val GlassBottom get() = current.glassBottom
    val GlassBorder get() = current.glassBorder
    val Magenta = Color(0xFFD946EF)
    val Red = Color(0xFFFF2A44)
    val Gold = Color(0xFFFBBF24)
    val Green = Color(0xFF34D399)
}

/** Motion/performance preferences, available to every composable. */
data class UiPrefs(val reducedMotion: Boolean = false, val lowPerformance: Boolean = false) {
    val animate: Boolean get() = !reducedMotion
    val rich: Boolean get() = !lowPerformance && !reducedMotion
}

val LocalUiPrefs = compositionLocalOf { UiPrefs() }

private fun schemeFor(p: Palette) = darkColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    secondary = p.tertiary,
    onSecondary = Color.Black,
    tertiary = p.secondary,
    background = p.bg,
    onBackground = p.textHi,
    surface = p.surface,
    onSurface = p.textHi,
    surfaceVariant = p.raised,
    onSurfaceVariant = p.textLo,
    error = UltronColors.Red,
    outline = p.glassBorder,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.raised,
)

private fun familyFor(f: AppFont): FontFamily = when (f) {
    AppFont.DEFAULT -> FontFamily.Default
    AppFont.CONDENSED -> FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal), Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold))
    AppFont.SERIF -> FontFamily.Serif
    AppFont.MONO -> FontFamily.Monospace
    AppFont.CASUAL -> FontFamily(Font(DeviceFontFamilyName("casual"), FontWeight.Normal))
    AppFont.CURSIVE -> FontFamily.Cursive
}

private fun typographyFor(font: AppFont): Typography {
    val fam = familyFor(font)
    val labelFam = if (font == AppFont.DEFAULT) FontFamily.Monospace else fam
    return Typography(
        headlineMedium = TextStyle(fontFamily = fam, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.2.sp),
        titleLarge = TextStyle(fontFamily = fam, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 4.sp),
        titleMedium = TextStyle(fontFamily = fam, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = fam, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = fam, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = fam, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = labelFam, fontSize = 11.sp, letterSpacing = 1.2.sp),
        labelMedium = TextStyle(fontFamily = labelFam, fontSize = 12.sp, letterSpacing = 1.sp),
    )
}

@Composable
fun UltronTheme(
    variant: ThemeVariant = ThemeVariant.NAVY,
    prefs: UiPrefs = UiPrefs(),
    font: AppFont = AppFont.DEFAULT,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // Brand is dark-only by design; isSystemInDarkTheme intentionally not consulted.
    @Suppress("UNUSED_VARIABLE") val ignored = isSystemInDarkTheme()
    val palette = remember(variant) { Palettes.of(variant).also { UltronColors.current = it } }
    val scheme = remember(palette) { schemeFor(palette) }
    val typography = remember(font) { typographyFor(font) }
    val d = LocalDensity.current
    val density = remember(d, textScale) { Density(d.density, d.fontScale * textScale) }
    CompositionLocalProvider(LocalUiPrefs provides prefs, LocalThemeVariant provides variant, LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            UltronBackground(variant) { content() }
        }
    }
}

val LocalThemeVariant = compositionLocalOf { ThemeVariant.NAVY }

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
                        listOf(UltronColors.Blue.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.12f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.85f, size.height * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(UltronColors.Violet.copy(alpha = 0.12f), Color.Transparent),
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
