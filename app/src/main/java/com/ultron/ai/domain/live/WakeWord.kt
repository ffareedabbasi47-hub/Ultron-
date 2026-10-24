package com.ultron.ai.domain.live

/**
 * Text-level wake phrase detection on a transcribed short utterance. This is NOT an acoustic
 * wake-word engine: it only works on text that an STT step already produced.
 */
object WakeWord {
    private val phrases = listOf("wake up ultron", "wakeup ultron", "hello ultron", "hey ultron", "hi ultron", "ultron")
    private val aliases = listOf("ultran", "altron", "ultrón", "अल्ट्रॉन", "अल्ट्रोन", "ultren", "oltron")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Returns the command after the wake phrase ("" if only the phrase), or null if no wake phrase. */
    fun match(text: String): String? {
        var t = norm(text)
        for (a in aliases) t = t.replace(a, "ultron")
        for (p in phrases) {
            val i = t.indexOf(p)
            if (i in 0..12) return t.substring(i + p.length).trim().trimStart(',', ' ')
        }
        return null
    }
}
