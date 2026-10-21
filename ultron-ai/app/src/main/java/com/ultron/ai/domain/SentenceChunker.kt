package com.ultron.ai.domain

/**
 * Turns streamed text deltas into speakable sentences so TTS can start before the full reply
 * arrives (incremental synthesis). Pure logic, no Android dependencies.
 */
class SentenceChunker(private val minChunkChars: Int = 24, private val maxChunkChars: Int = 280) {
    private val buffer = StringBuilder()

    /** Feed a delta; returns zero or more complete chunks ready to speak. */
    fun feed(delta: String): List<String> {
        buffer.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = findCut() ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (chunk.isNotEmpty()) out += chunk
        }
        return out
    }

    /** Call at end of stream; returns the remaining text, if any. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifEmpty { null }
    }

    fun reset() = buffer.clear()

    private fun findCut(): Int? {
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            val isEnd = c == '.' || c == '!' || c == '?' || c == '।' || c == '\n'
            if (isEnd && i + 1 >= minChunkChars) {
                val next = buffer.getOrNull(i + 1)
                // Do not cut inside "3.5" or "e.g.x": require whitespace/end after the mark.
                if (next == null || next.isWhitespace()) {
                    if (next == null && c != '\n') return null // wait: more text may follow
                    return i + 1
                }
            }
            i++
        }
        if (buffer.length >= maxChunkChars) {
            val space = buffer.lastIndexOf(" ", maxChunkChars)
            return if (space > minChunkChars) space + 1 else maxChunkChars
        }
        return null
    }
}
