package com.ultron.ai.domain.live

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Mouth pose for one 20 ms slice of the audio that is really being played. */
class MouthFrames(val open: FloatArray, val shape: FloatArray, val frameMs: Int) {
    val size: Int get() = open.size
}

/**
 * Real audio-driven lip-sync: loudness drives how far the jaw opens, and the zero-crossing rate (a cheap
 * brightness measure) shapes the mouth: low = round/open vowels (aa, oo), high = thin/wide for s, sh, ee.
 * It is computed from the exact PCM that goes to the speaker, so it is in time with what you hear.
 * It is amplitude/brightness based, not phoneme based: good, natural-looking sync, not exact visemes.
 */
object LipSync {
    fun analyze(pcm: ShortArray, sampleRate: Int, frameMs: Int = 20): MouthFrames {
        val n = sampleRate * frameMs / 1000
        val count = (pcm.size + n - 1) / n
        val open = FloatArray(count)
        val shape = FloatArray(count)
        var prevOpen = 0f
        for (f in 0 until count) {
            val from = f * n
            val to = minOf(pcm.size, from + n)
            var sum = 0.0; var zc = 0
            for (i in from until to) {
                val v = pcm[i] / 32768.0
                sum += v * v
                if (i > from && (pcm[i] >= 0) != (pcm[i - 1] >= 0)) zc++
            }
            val len = maxOf(1, to - from)
            val rms = sqrt(sum / len)
            val db = if (rms < 1e-5) -100.0 else 20 * log10(rms)
            // -46 dB (barely audible) .. -16 dB (loud speech) -> 0..1, then a soft curve so quiet syllables still move.
            val raw = ((db + 46) / 30).coerceIn(0.0, 1.0).let { Math.pow(it, 0.8) }.toFloat()
            // fast attack, slower release, like a real jaw
            val o = if (raw > prevOpen) prevOpen + (raw - prevOpen) * 0.7f else prevOpen + (raw - prevOpen) * 0.35f
            prevOpen = o
            open[f] = if (o < 0.04f) 0f else o
            val zcr = zc.toFloat() / len                // 0..0.5
            shape[f] = if (raw < 0.08f) 0f else ((zcr - 0.10f) / 0.14f).coerceIn(-1f, 1f)
        }
        return MouthFrames(open, shape, frameMs)
    }

    /** Frame for a playback position (in samples since the start of this clip). Clamped; silent past the end. */
    fun at(frames: MouthFrames, sampleRate: Int, positionSamples: Long): Pair<Float, Float> {
        if (positionSamples < 0) return 0f to 0f
        val idx = (positionSamples * 1000 / sampleRate / frames.frameMs).toInt()
        if (idx >= frames.size) return 0f to 0f
        return frames.open[idx] to frames.shape[idx]
    }

    fun peak(frames: MouthFrames): Float = frames.open.maxOrNull()?.let { abs(it) } ?: 0f
}
