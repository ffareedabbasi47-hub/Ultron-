package com.ultron.ai.domain.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** MFCC features (12 coefficients + log energy) with per-utterance mean/variance normalisation. Pure Kotlin. */
object Mfcc {
    const val RATE = 16_000
    private const val FRAME = 400      // 25 ms
    private const val HOP = 160        // 10 ms
    private const val NFFT = 512
    private const val MELS = 26
    const val DIM = 13

    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private val bank: Array<DoubleArray> by lazy { melBank() }
    private val dct: Array<DoubleArray> = Array(DIM - 1) { k -> DoubleArray(MELS) { n -> cos(PI * (k + 1) * (n + 0.5) / MELS) } }

    private fun hz2mel(f: Double) = 2595 * log10(1 + f / 700)
    private fun mel2hz(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)

    private fun melBank(): Array<DoubleArray> {
        val lo = hz2mel(100.0); val hi = hz2mel(RATE / 2.0 - 200)
        val pts = DoubleArray(MELS + 2) { mel2hz(lo + (hi - lo) * it / (MELS + 1)) }
        val bins = IntArray(MELS + 2) { ((NFFT + 1) * pts[it] / RATE).toInt().coerceIn(0, NFFT / 2) }
        return Array(MELS) { m ->
            DoubleArray(NFFT / 2 + 1).also { f ->
                for (k in bins[m] until bins[m + 1]) f[k] = (k - bins[m]).toDouble() / max(1, bins[m + 1] - bins[m])
                for (k in bins[m + 1] until bins[m + 2]) f[k] = (bins[m + 2] - k).toDouble() / max(1, bins[m + 2] - bins[m + 1])
            }
        }
    }

    /** @return frames x DIM (whole-utterance mean/variance normalised), or an empty array if the audio is shorter than one frame. */
    fun compute(pcm: ShortArray): Array<FloatArray> = computeRaw(pcm).also { cmvn(it) }

    /**
     * Same features WITHOUT normalisation. The wake matcher normalises only the leading window of an utterance
     * ([normalizeWindow]), so a command spoken right after the wake phrase cannot distort the phrase's features.
     */
    fun computeRaw(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < FRAME) return emptyArray()
        val n = 1 + (pcm.size - FRAME) / HOP
        val out = Array(n) { FloatArray(DIM) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val logMel = DoubleArray(MELS)
        for (f in 0 until n) {
            val s = f * HOP
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            var energy = 0.0
            for (i in 0 until FRAME) {
                val cur = pcm[s + i] / 32768.0
                val prev = if (s + i > 0) pcm[s + i - 1] / 32768.0 else 0.0
                val v = (cur - 0.97 * prev) * window[i]
                re[i] = v; energy += v * v
            }
            fft(re, im)
            for (m in 0 until MELS) {
                var acc = 0.0
                val fb = bank[m]
                for (k in 0..NFFT / 2) if (fb[k] != 0.0) acc += fb[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[m] = ln(max(acc, 1e-10))
            }
            for (k in 0 until DIM - 1) { var a = 0.0; for (m in 0 until MELS) a += dct[k][m] * logMel[m]; out[f][k] = a.toFloat() }
            out[f][DIM - 1] = ln(max(energy, 1e-10)).toFloat()
        }
        return out
    }

    /** Copy of [raw] normalised with the mean/variance of only its first [window] frames. */
    fun normalizeWindow(raw: Array<FloatArray>, window: Int): Array<FloatArray> {
        if (raw.isEmpty()) return raw
        val w = window.coerceIn(1, raw.size)
        val out = Array(raw.size) { raw[it].copyOf() }
        for (d in 0 until DIM) {
            var mean = 0.0; for (i in 0 until w) mean += raw[i][d]; mean /= w
            var v = 0.0; for (i in 0 until w) { val t = raw[i][d] - mean; v += t * t }
            val sd = sqrt(v / w).coerceAtLeast(1e-3)
            for (f in out) f[d] = ((f[d] - mean) / sd).toFloat()
        }
        return out
    }

    private fun cmvn(x: Array<FloatArray>) {
        if (x.isEmpty()) return
        for (d in 0 until DIM) {
            var mean = 0.0; for (f in x) mean += f[d]; mean /= x.size
            var v = 0.0; for (f in x) { val t = f[d] - mean; v += t * t }
            val sd = sqrt(v / x.size).coerceAtLeast(1e-3)
            for (f in x) f[d] = ((f[d] - mean) / sd).toFloat()
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
