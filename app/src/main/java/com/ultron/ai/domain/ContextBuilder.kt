package com.ultron.ai.domain

data class ChatTurn(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

object ContextBuilder {
    /**
     * Bounded history: at most [maxTurns] turns and [maxChars] characters, newest preferred,
     * always starting with a user turn (provider requirement). The newest turn is always kept
     * (truncated if it alone exceeds [maxChars]).
     */
    fun bound(turns: List<ChatTurn>, maxTurns: Int, maxChars: Int): List<ChatTurn> {
        if (turns.isEmpty() || maxTurns <= 0 || maxChars <= 0) return emptyList()
        val recent = turns.takeLast(maxTurns)
        val kept = ArrayDeque<ChatTurn>()
        var total = 0
        for (turn in recent.asReversed()) {
            val len = turn.text.length
            if (kept.isNotEmpty() && total + len > maxChars) break
            val t = if (len > maxChars) turn.copy(text = turn.text.takeLast(maxChars)) else turn
            total += t.text.length
            kept.addFirst(t)
        }
        while (kept.isNotEmpty() && kept.first().role != ChatTurn.USER) kept.removeFirst()
        return kept.toList()
    }
}
