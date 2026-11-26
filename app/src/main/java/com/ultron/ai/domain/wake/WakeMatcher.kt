package com.ultron.ai.domain.wake

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/** One enrolled recording of the wake phrase, stored only as normalised MFCC features (never raw audio). */
class WakeTemplate(val features: Array<FloatArray>)

/**
 * Personal, fully offline wake phrase detector: dynamic time warping (DTW) between the features of what was just
 * said and the user's own enrolled recordings of "Hey Ultron". It is speaker-dependent (a different voice or a
 * video voice scores worse) and language independent. It is NOT a trained neural keyword spotter, so expect some
 * misses and false triggers; the sensitivity setting trades one for the other.
 */
object WakeMatcher {
    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    /** Whole-sequence DTW distance (normalised by path length). Used between enrollment samples. */
    fun fullDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float = dtw(a, b, openEnd = false)

    /**
     * Open-end DTW: [template] must match the START of [input]; anything spoken after it (a command) is ignored.
     */
    fun prefixDistance(template: Array<FloatArray>, input: Array<FloatArray>): Float = dtw(template, input, openEnd = true)

    private fun dtw(t: Array<FloatArray>, x: Array<FloatArray>, openEnd: Boolean): Float {
        val n = t.size; val m = x.size
        if (n == 0 || m == 0) return Float.MAX_VALUE
        val inf = Float.MAX_VALUE / 4
        // Local slope limit: the input may be ~0.6x..1.7x the speed of the template.
        var prev = FloatArray(m + 1) { inf }; var cur = FloatArray(m + 1) { inf }
        var prevLen = IntArray(m + 1); var curLen = IntArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            java.util.Arrays.fill(cur, inf)
            val jMin = maxOf(1, (i * 0.55f).toInt() - 2)
            val jMax = minOf(m, (i * 1.8f).toInt() + 2)
            for (j in jMin..jMax) {
                val c = dist(t[i - 1], x[j - 1])
                var best = prev[j - 1]; var bl = prevLen[j - 1]
                if (prev[j] < best) { best = prev[j]; bl = prevLen[j] }
                if (cur[j - 1] < best) { best = cur[j - 1]; bl = curLen[j - 1] }
                if (best < inf) { cur[j] = best + c; curLen[j] = bl + 1 }
            }
            val tp = prev; prev = cur; cur = tp
            val tl = prevLen; prevLen = curLen; curLen = tl
        }
        var best = Float.MAX_VALUE
        if (openEnd) {
            val lo = maxOf(1, (n * 0.6f).toInt()); val hi = minOf(m, (n * 1.7f).toInt() + 1)
            for (j in lo..hi) if (prev[j] < inf) best = minOf(best, prev[j] / prevLen[j].coerceAtLeast(1))
        } else if (prev[m] < inf) best = prev[m] / prevLen[m].coerceAtLeast(1)
        return best
    }

    /** Average pairwise distance of the enrolled samples: how consistent the user's own recordings are. */
    fun intraDistance(ts: List<WakeTemplate>): Float {
        if (ts.size < 2) return 0f
        var sum = 0f; var c = 0
        for (i in ts.indices) for (j in i + 1 until ts.size) { sum += fullDistance(ts[i].features, ts[j].features); c++ }
        return sum / c
    }

    /** Score of an utterance: mean of the two best template distances (more robust than one lucky template). */
    fun score(ts: List<WakeTemplate>, input: Array<FloatArray>): Float {
        if (ts.isEmpty() || input.isEmpty()) return Float.MAX_VALUE
        val d = ts.map { prefixDistance(it.features, input) }.sorted()
        return if (d.size >= 2) (d[0] + d[1]) / 2 else d[0]
    }

    /**
     * Like [score], but [raw] is NOT normalised yet: for every template the input is normalised over only its first
     * ~1.15 template lengths, so "Hey Ultron, open youtube" in one breath matches as well as the bare phrase.
     */
    fun scoreWindowed(ts: List<WakeTemplate>, raw: Array<FloatArray>): Float {
        if (ts.isEmpty() || raw.isEmpty()) return Float.MAX_VALUE
        val d = ts.map { t -> prefixDistance(t.features, Mfcc.normalizeWindow(raw, (t.features.size * 1.15f).toInt())) }.sorted()
        return if (d.size >= 2) (d[0] + d[1]) / 2 else d[0]
    }

    /** Decision threshold from the user's own samples; sensitivity 1.0 = default, >1 accepts more. */
    fun threshold(ts: List<WakeTemplate>, sensitivity: Float): Float {
        val base = maxOf(intraDistance(ts), 0.6f)
        return base * 1.25f * sensitivity.coerceIn(0.5f, 2f)
    }

    // ---- storage (features only)
    fun serialize(ts: List<WakeTemplate>): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeInt(0x554C5457); o.writeInt(ts.size)
        for (t in ts) { o.writeInt(t.features.size); o.writeInt(Mfcc.DIM); for (f in t.features) for (v in f) o.writeFloat(v) }
        return bo.toByteArray()
    }

    fun deserialize(b: ByteArray): List<WakeTemplate> {
        val i = DataInputStream(ByteArrayInputStream(b))
        if (i.readInt() != 0x554C5457) return emptyList()
        val n = i.readInt().coerceIn(0, 20)
        return List(n) {
            val frames = i.readInt().coerceIn(0, 2000); val dim = i.readInt()
            require(dim == Mfcc.DIM)
            WakeTemplate(Array(frames) { FloatArray(dim) { i.readFloat() } })
        }
    }
}
