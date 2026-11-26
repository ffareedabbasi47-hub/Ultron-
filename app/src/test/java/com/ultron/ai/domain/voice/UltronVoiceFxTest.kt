package com.ultron.ai.domain.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltronVoiceFxTest {
    private val rate = 22050
    private fun tone(hz: Double, ms: Int, amp: Double = 8000.0) = ShortArray(rate * ms / 1000) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }
    private fun rms(a: ShortArray, from: Int = 0, to: Int = a.size): Double { var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return Math.sqrt(s / (to - from).coerceAtLeast(1)) }

    @Test fun stayInRangeAndNotSilent() {
        val out = UltronVoiceFx.process(tone(180.0, 1500), rate, 1, 3)
        assertTrue(out.maxOf { abs(it.toInt()) } in 3000..32767)
    }
    @Test fun deterministicForSameSeed() {
        val a = UltronVoiceFx.process(tone(150.0, 1000), rate, 1, 9); val b = UltronVoiceFx.process(tone(150.0, 1000), rate, 1, 9)
        assertTrue(a.contentEquals(b))
    }
    @Test fun breathAddsAudioBeforeVoiceAndFirstIsShorter() {
        val v = tone(150.0, 1200)
        val none = UltronVoiceFx.process(v, rate, 1, 1, UltronVoiceFx.Options(fx = true, breath = false))
        val first = UltronVoiceFx.process(v, rate, 0, 1)
        val later = UltronVoiceFx.process(v, rate, 1, 1)
        assertTrue(first.size > none.size); assertTrue(later.size > first.size)
        assertTrue(rms(later, 0, rate / 10) > 20.0) // the breath itself is audible noise
    }
    @Test fun shortClipGetsNoBreathUnlessFirst() {
        val v = tone(150.0, 300)
        val plain = UltronVoiceFx.process(v, rate, 2, 1, UltronVoiceFx.Options(true, false))
        assertEquals(plain.size, UltronVoiceFx.process(v, rate, 2, 1).size)
    }
    @Test fun lowFrequenciesGetMoreBodyThanHighOnes() {
        fun gain(hz: Double): Double { val x = tone(hz, 800); val y = UltronVoiceFx.shape(x, rate); return rms(y, rate / 5, rate / 2) / rms(x, rate / 5, rate / 2) }
        // normalisation changes absolute level, so compare the two tones relative to each other
        assertTrue(gain(120.0) > gain(4000.0) * 1.0)
    }
    @Test fun breathBoundedAndShaped() {
        val b = UltronVoiceFx.breath(rate / 4, rate, 3000f, 5, true)
        assertTrue(abs(b[0].toInt()) < 400); assertTrue(b.maxOf { abs(it.toInt()) } > 200)
    }
}
