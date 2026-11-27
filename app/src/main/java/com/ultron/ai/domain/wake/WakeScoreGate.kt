package com.ultron.ai.domain.wake

/**
 * Turns a stream of per-80ms neural wake scores into at most ONE trigger per utterance.
 * - [threshold]: score a chunk must reach (0..1).
 * - [patience]: how many consecutive chunks must reach it (cuts single-chunk spikes).
 * - [debounceMs]: after a trigger, everything is ignored for this long (no duplicate sessions).
 */
class WakeScoreGate(var threshold: Float = 0.5f, var patience: Int = 2, var debounceMs: Long = 2500L) {
    private var streak = 0
    private var blockedUntil = Long.MIN_VALUE

    fun onScore(score: Float, nowMs: Long): Boolean {
        if (nowMs < blockedUntil) { streak = 0; return false }
        if (score >= threshold) streak++ else streak = 0
        if (streak >= patience.coerceAtLeast(1)) {
            streak = 0
            blockedUntil = nowMs + debounceMs
            return true
        }
        return false
    }

    fun reset() { streak = 0; blockedUntil = Long.MIN_VALUE }

    companion object {
        /** Higher user sensitivity (0.5..2) lowers the threshold; default 1.0 -> 0.5. */
        fun thresholdFor(sensitivity: Float): Float = (0.5f / sensitivity.coerceIn(0.5f, 2f)).coerceIn(0.25f, 0.9f)
    }
}

/** Re-slices arbitrary mic frames into exact fixed-size chunks (openWakeWord needs 1280 samples = 80 ms @ 16 kHz). */
class ChunkBuffer(private val size: Int = 1280) {
    private val buf = ShortArray(size)
    private var fill = 0

    fun push(frame: ShortArray, onChunk: (ShortArray) -> Unit) {
        var i = 0
        while (i < frame.size) {
            val n = minOf(size - fill, frame.size - i)
            System.arraycopy(frame, i, buf, fill, n)
            fill += n; i += n
            if (fill == size) { onChunk(buf.copyOf()); fill = 0 }
        }
    }

    fun clear() { fill = 0 }
}
