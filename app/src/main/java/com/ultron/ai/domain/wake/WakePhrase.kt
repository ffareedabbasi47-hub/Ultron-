package com.ultron.ai.domain.wake

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** The three wake phrases ULTRON understands, each with its own spoken greeting. */
enum class WakePhrase(val spoken: String, val greeting: String) {
    HEY("Hey ULTRON", "Yes Boss?"),
    HELLO("Hello ULTRON", "Yes Boss?"),
    WAKE_UP("Wake up ULTRON", "I'm awake, Boss.");
}

/** Result of one wake check. [phrase] is null when nothing matched; score/threshold belong to the closest phrase (diagnostics). */
data class WakeDecision(val phrase: WakePhrase?, val score: Float, val threshold: Float, val note: String) {
    val match: Boolean get() = phrase != null
}

/**
 * Multi-phrase, fully offline acoustic wake decision built on [WakeMatcher] (MFCC + open-end DTW against the user's own
 * recordings). Every phrase has its own templates and its own threshold. It is speaker dependent and is NOT a trained
 * neural keyword spotter: expect some misses / false triggers, tune with the sensitivity setting.
 */
object PhraseWakeMatcher {
    const val MIN_TEMPLATES = 3
    /** Input may be at most this many times longer than the longest template (room for a trailing command). */
    private const val MAX_INPUT_FACTOR = 3.2f
    /** Two phrases this close (in score/threshold ratio) with different replies are treated as ambiguous. */
    private const val AMBIGUITY_MARGIN = 0.05f
    private const val FRAME_MS = 10L

    fun ready(sets: Map<WakePhrase, List<WakeTemplate>>): Boolean = sets.values.any { it.size >= MIN_TEMPLATES }

    /** Longest utterance (ms) worth running the matcher on; longer speech (TV, conversation) is skipped before any DTW cost. */
    fun maxUtteranceMs(sets: Map<WakePhrase, List<WakeTemplate>>): Long {
        val frames = sets.values.filter { it.size >= MIN_TEMPLATES }.flatten().maxOfOrNull { it.features.size } ?: return 0L
        return (frames * MAX_INPUT_FACTOR).toLong() * FRAME_MS
    }

    /** @param input RAW features from [Mfcc.computeRaw] (normalisation is done per template window). */
    fun decide(sets: Map<WakePhrase, List<WakeTemplate>>, input: Array<FloatArray>, sensitivity: Float): WakeDecision {
        val usable = sets.filter { it.value.size >= MIN_TEMPLATES }
        if (usable.isEmpty()) return WakeDecision(null, Float.MAX_VALUE, 0f, "no templates")
        if (input.size < 20) return WakeDecision(null, Float.MAX_VALUE, 0f, "too short")
        val maxT = usable.values.maxOf { ts -> ts.maxOf { it.features.size } }
        if (input.size > maxT * MAX_INPUT_FACTOR) return WakeDecision(null, Float.MAX_VALUE, 0f, "too long")

        data class Cand(val phrase: WakePhrase, val score: Float, val thr: Float) { val ratio get() = score / thr }
        val cands = usable.map { (p, ts) -> Cand(p, WakeMatcher.scoreWindowed(ts, input), WakeMatcher.threshold(ts, sensitivity)) }.sortedBy { it.ratio }
        val best = cands.first()
        if (best.ratio > 1f) return WakeDecision(null, best.score, best.thr, "no match")
        val second = cands.getOrNull(1)
        if (second != null && second.ratio <= 1f && second.phrase.greeting != best.phrase.greeting && second.ratio - best.ratio < AMBIGUITY_MARGIN) {
            return WakeDecision(null, maxOf(best.score, best.thr * 1.01f), best.thr, "ambiguous ${best.phrase}/${second.phrase}")
        }
        return WakeDecision(best.phrase, best.score, best.thr, "match ${best.phrase}")
    }
}

/** After a wake the engine ignores further wake triggers for a short time (double fires, echo of the greeting). */
class WakeCooldown(private val ms: Long = 1500L) {
    private var last = Long.MIN_VALUE
    fun allow(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < ms) return false
        last = nowMs
        return true
    }
}

/** Storage of all phrases' templates in one file (features only, never audio). */
object WakePhraseStore {
    private const val MAGIC = 0x554C5750 // "ULWP"

    fun serialize(sets: Map<WakePhrase, List<WakeTemplate>>): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        val entries = sets.filter { it.value.isNotEmpty() }
        o.writeInt(MAGIC); o.writeInt(entries.size)
        for ((p, ts) in entries) {
            val bytes = WakeMatcher.serialize(ts)
            o.writeInt(p.ordinal); o.writeInt(bytes.size); o.write(bytes)
        }
        return bo.toByteArray()
    }

    fun deserialize(b: ByteArray): Map<WakePhrase, List<WakeTemplate>> {
        return try {
            val i = DataInputStream(ByteArrayInputStream(b))
            if (i.readInt() != MAGIC) return emptyMap()
            val n = i.readInt().coerceIn(0, WakePhrase.entries.size)
            val out = LinkedHashMap<WakePhrase, List<WakeTemplate>>()
            repeat(n) {
                val ord = i.readInt(); val len = i.readInt().coerceIn(0, 4_000_000)
                val buf = ByteArray(len); i.readFully(buf)
                val p = WakePhrase.entries.getOrNull(ord)
                if (p != null) out[p] = WakeMatcher.deserialize(buf)
            }
            out
        } catch (_: Exception) { emptyMap() }
    }
}
