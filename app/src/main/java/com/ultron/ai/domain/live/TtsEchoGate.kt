package com.ultron.ai.domain.live

/**
 * Decides whether captured audio may be treated as the USER while ULTRON is speaking.
 * With working acoustic echo cancellation the recorder keeps listening (barge-in with a higher threshold).
 * Without effective AEC, utterances that start during playback (and for a short tail afterwards, while the
 * room echo decays) are discarded. The recorder itself is never closed.
 */
class TtsEchoGate(private val tailMs: Long = 450L) {
    private var speaking = false
    private var endedAt = Long.MIN_VALUE

    fun onSpeechStart() { speaking = true }
    fun onSpeechEnd(nowMs: Long) { speaking = false; endedAt = nowMs }

    val assistantSpeaking: Boolean get() = speaking

    /** True if an utterance that began at [startMs] could be ULTRON's own voice and must be ignored. */
    fun shouldDiscard(startMs: Long, nowMs: Long, aecEffective: Boolean): Boolean {
        if (aecEffective) return false
        if (speaking) return true
        return endedAt != Long.MIN_VALUE && startMs - endedAt < tailMs
    }
}
