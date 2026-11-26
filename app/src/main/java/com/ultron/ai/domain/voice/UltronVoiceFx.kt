package com.ultron.ai.domain.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Offline "menacing machine" voice treatment applied to the phone's own TTS audio, plus synthetic breaths.
 * Original effect chain (low-end body, raspy ring modulation, a short metallic comb, a small hall, soft clipping);
 * it does NOT imitate any actor. All math is on raw PCM, no network, no Android classes (unit-tested on the JVM).
 */
object UltronVoiceFx {
    class Options(val fx: Boolean = true, val breath: Boolean = true)

    /** @param clipIndex 0 for the first sentence of a reply (short breath, so the voice starts quickly). */
    fun process(pcm: ShortArray, rate: Int, clipIndex: Int, seed: Int, opt: Options = Options()): ShortArray {
        if (pcm.isEmpty() || rate <= 0) return pcm
        val voice = if (opt.fx) shape(pcm, rate) else pcm
        if (!opt.breath) return voice
        val longEnough = pcm.size >= rate * 7 / 10 // ~0.7 s: a breath before a one-word reply sounds silly
        val inhaleMs = if (clipIndex == 0) 140 else if (longEnough) 300 else 0
        if (inhaleMs == 0) return voice
        val peak = voice.maxOf { abs(it.toInt()) }.coerceAtLeast(1000)
        val inhale = breath(rate * inhaleMs / 1000, rate, amp = peak * 0.16f, seed = seed, inhale = true)
        val gap = ShortArray(rate * 35 / 1000)
        val exhale = if (longEnough && pcm.size >= rate * 2) breath(rate * 160 / 1000, rate, amp = peak * 0.07f, seed = seed + 7, inhale = false) else ShortArray(0)
        val out = ShortArray(inhale.size + gap.size + voice.size + exhale.size)
        System.arraycopy(inhale, 0, out, 0, inhale.size)
        System.arraycopy(voice, 0, out, inhale.size + gap.size, voice.size)
        System.arraycopy(exhale, 0, out, inhale.size + gap.size + voice.size, exhale.size)
        return out
    }

    fun shape(pcm: ShortArray, rate: Int): ShortArray {
        val tail = rate * 260 / 1000
        val n = pcm.size + tail
        val x = FloatArray(n)
        for (i in pcm.indices) x[i] = pcm[i] / 32768f

        // 1) body: boost below ~220 Hz, trim hiss above ~5.5 kHz
        val aLo = 1f - exp(-2.0 * PI * 220.0 / rate).toFloat()
        val aHi = 1f - exp(-2.0 * PI * 5500.0 / rate).toFloat()
        var lo = 0f; var hi = 0f
        val y = FloatArray(n)
        for (i in 0 until n) {
            lo += aLo * (x[i] - lo)
            hi += aHi * (x[i] - hi)
            y[i] = x[i] + 0.95f * lo - 0.18f * (x[i] - hi)
        }
        // 2) rasp: ring modulation at a low frequency, mixed in
        val f = 62.0
        for (i in 0 until n) y[i] = y[i] * (1f - 0.22f) + y[i] * sin(2.0 * PI * f * i / rate).toFloat() * 0.22f * 1.6f
        // 3) metallic resonance: short feedback comb (~8.5 ms)
        val d = max(2, (rate * 0.0085).toInt())
        val c = FloatArray(n)
        for (i in 0 until n) c[i] = y[i] + (if (i >= d) 0.38f * c[i - d] else 0f)
        for (i in 0 until n) c[i] *= 0.68f
        // 4) small hall
        val taps = intArrayOf(rate * 31 / 1000, rate * 57 / 1000, rate * 97 / 1000)
        val gains = floatArrayOf(0.22f, 0.15f, 0.10f)
        val r = FloatArray(n)
        for (i in 0 until n) {
            var v = c[i]
            for (t in taps.indices) if (i >= taps[t]) v += gains[t] * c[i - taps[t]]
            r[i] = v
        }
        // 5) soft clip + normalise
        val k = 1.6f
        val norm = tanh(k)
        var peak = 1e-6f
        for (i in 0 until n) { r[i] = tanh(k * r[i]) / norm; peak = max(peak, abs(r[i])) }
        val g = 0.89f / peak
        return ShortArray(n) { (r[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Band-limited noise with a rise/fall envelope: "hhhh" of air being drawn in (or let out). */
    fun breath(len: Int, rate: Int, amp: Float, seed: Int, inhale: Boolean): ShortArray {
        if (len <= 0) return ShortArray(0)
        var s = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        fun rnd(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 33).toInt() / 2147483648f) * 2f - 1f }
        val aHp = 1f - exp(-2.0 * PI * 350.0 / rate).toFloat()
        val aLp = 1f - exp(-2.0 * PI * 3200.0 / rate).toFloat()
        var hp = 0f; var lp = 0f
        val out = ShortArray(len)
        for (i in 0 until len) {
            val noise = rnd()
            lp += aLp * (noise - lp)
            hp += aHp * (lp - hp)
            val band = lp - hp
            val t = i / len.toFloat()
            val env = if (inhale) (if (t < 0.7f) sin(PI * t / 1.4).toFloat() else sin(PI * (1f - t) / 0.6).toFloat()) else (1f - t) * (1f - t)
            val flutter = 0.85f + 0.15f * sin(2.0 * PI * 9.0 * i / rate).toFloat()
            out[i] = (band * env.coerceAtLeast(0f) * flutter * amp * 4f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
