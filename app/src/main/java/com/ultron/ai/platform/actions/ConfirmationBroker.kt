package com.ultron.ai.platform.actions

import com.ultron.ai.domain.AgentProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One place where a risky step waits for the user. It can be answered by voice (live session text) or by the
 * Haan / Nahi buttons on Home. No answer within the timeout counts as NO.
 */
class ConfirmationBroker {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending
    @Volatile private var waiter: CompletableDeferred<Boolean>? = null

    suspend fun ask(question: String, speak: (String) -> Unit, timeoutMs: Long = 25_000): Boolean {
        val d = CompletableDeferred<Boolean>()
        waiter = d
        _pending.value = question
        speak(question)
        val answer = withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        _pending.value = null
        waiter = null
        return answer
    }

    fun answer(yes: Boolean) { waiter?.complete(yes) }

    /** Voice path: returns true if the text was consumed as an answer to the pending question. */
    fun answerFromText(text: String): Boolean {
        if (waiter == null) return false
        val v = AgentProtocol.yesNo(text) ?: return false
        answer(v)
        return true
    }

    val isPending: Boolean get() = waiter != null
}
