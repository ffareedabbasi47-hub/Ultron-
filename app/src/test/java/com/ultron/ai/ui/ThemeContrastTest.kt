package com.ultron.ai.ui

import androidx.compose.ui.graphics.Color
import com.ultron.ai.data.settings.ThemeVariant
import com.ultron.ai.ui.theme.Palettes
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX gate (ui-ux-pro-max, accessibility): normal text must keep >= 4.5:1 on every theme surface. */
class ThemeContrastTest {
    private fun lum(c: Color): Double {
        fun f(v: Float): Double { val x = v.toDouble(); return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double { val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending(); return (hi + 0.05) / (lo + 0.05) }

    @Test fun textIsReadableOnEveryThemeSurface() {
        for (v in ThemeVariant.values()) {
            val p = Palettes.of(v)
            for ((name, bg) in listOf("bg" to p.bg, "bgDeep" to p.bgDeep, "surface" to p.surface, "raised" to p.raised)) {
                assertTrue("$v textHi on $name", ratio(p.textHi, bg) >= 4.5)
                assertTrue("$v textLo on $name", ratio(p.textLo, bg) >= 4.5)
            }
            assertTrue("$v primary on bg (non-text 3:1)", ratio(p.primary, p.bg) >= 3.0)
        }
    }
}
