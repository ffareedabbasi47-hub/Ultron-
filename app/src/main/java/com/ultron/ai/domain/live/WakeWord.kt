package com.ultron.ai.domain.live

import com.ultron.ai.domain.wake.WakePhrase

/** A recognised wake phrase in text and what was said after it. [phrase] is null only for the bare word "ultron". */
data class WakeHit(val phrase: WakePhrase?, val rest: String)

/**
 * Text-level wake phrase detection on a transcribed short utterance. This is NOT an acoustic
 * wake-word engine: it only works on text that an STT step already produced.
 */
object WakeWord {
    private class P(val text: String, val phrase: WakePhrase?, val bare: Boolean = false)
    private val phrases = listOf(
        P("wake up ultron", WakePhrase.WAKE_UP), P("wakeup ultron", WakePhrase.WAKE_UP),
        P("hello ultron", WakePhrase.HELLO),
        P("hey ultron", WakePhrase.HEY), P("hi ultron", WakePhrase.HEY),
        P("ultron", null, bare = true),
    )
    private val aliases = listOf("ultran", "altron", "ultrón", "अल्ट्रॉन", "अल्ट्रोन", "ultren", "oltron")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Finds a wake phrase at the start of [text]. With [allowBare] = false only the three real phrases count
     * (used while ULTRON is already awake, so "ultron kaun hai" is never cut to "kaun hai").
     */
    fun parse(text: String, allowBare: Boolean = true): WakeHit? {
        var t = norm(text)
        for (a in aliases) t = t.replace(a, "ultron")
        for (p in phrases) {
            if (p.bare && !allowBare) continue
            val i = t.indexOf(p.text)
            if (i in 0..12) return WakeHit(p.phrase, t.substring(i + p.text.length).trim().trimStart(',', ' '))
        }
        return null
    }

    /** Returns the command after the wake phrase ("" if only the phrase), or null if no wake phrase. */
    fun match(text: String): String? = parse(text)?.rest
}
