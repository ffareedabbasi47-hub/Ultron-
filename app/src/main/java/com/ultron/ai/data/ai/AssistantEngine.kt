package com.ultron.ai.data.ai

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.core.diagnostics.LatencyStats
import com.ultron.ai.data.db.ConversationDao
import com.ultron.ai.data.db.ConversationEntity
import com.ultron.ai.data.db.MessageEntity
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.ActionRunner
import com.ultron.ai.domain.ActionValidator
import com.ultron.ai.domain.ChatTurn
import com.ultron.ai.domain.CommandParser
import com.ultron.ai.domain.ContextBuilder
import com.ultron.ai.domain.IdentityResponder
import com.ultron.ai.domain.SmallTalk
import com.ultron.ai.domain.Persona
import com.ultron.ai.domain.UltronAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface AssistantEvent {
    /** A reply produced on-device (identity answer or a validated device action). */
    data class Local(val text: String, val action: UltronAction? = null, val success: Boolean = true) : AssistantEvent
    data class Delta(val text: String) : AssistantEvent
    data class Done(val fullText: String, val firstTokenMs: Long?, val totalMs: Long, val totalTokens: Int?) : AssistantEvent
}

/**
 * Single entry point for "the user said something": identity fast-path -> typed device action
 * -> cloud AI (only with consent). Persists the exchange, bounds context, retries safely and
 * records measured latency.
 */
class AssistantEngine(
    private val dao: ConversationDao,
    private val provider: AIProvider,
    private val settings: SettingsRepository,
    private val identity: IdentityResponder,
    private val smallTalk: SmallTalk,
    private val actions: ActionRunner,
    private val stats: LatencyStats,
    private val diagnostics: DiagnosticsLog,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun newConversation(mode: ChatMode): Long {
        val t = now()
        return dao.insert(ConversationEntity(title = "Nayi baat", mode = mode.wire, createdAt = t, updatedAt = t))
    }

    suspend fun latestOrNew(mode: ChatMode): Long = dao.latest(mode.wire)?.id ?: newConversation(mode)

    fun respond(
        conversationId: Long,
        userText: String,
        mode: ChatMode,
        persistUser: Boolean = true,
    ): Flow<AssistantEvent> = flow {
        val text = userText.trim()
        require(text.isNotEmpty()) { "empty message" }
        val s = settings.settings.first()

        if (persistUser) {
            dao.insertMessage(MessageEntity(conversationId = conversationId, role = ChatTurn.USER, text = text, createdAt = now()))
            touch(conversationId, text)
        }

        // 1) identity: identical online/offline/voice/text
        identity.respond(text)?.let { reply ->
            saveAssistant(conversationId, reply, MessageEntity.SOURCE_LOCAL)
            emit(AssistantEvent.Local(reply))
            return@flow
        }

        // 1b) instant in-character small talk (greetings, thanks, time/date): no network, no wait
        smallTalk.respond(text)?.let { reply ->
            saveAssistant(conversationId, reply, MessageEntity.SOURCE_LOCAL)
            emit(AssistantEvent.Local(reply))
            return@flow
        }

        // 2) typed, validated device action
        CommandParser.parse(text)?.let { action ->
            val v = ActionValidator.validate(action)
            val reply: AssistantEvent.Local = if (!v.ok) {
                AssistantEvent.Local(v.reason ?: "Command valid nahi hai.", action, success = false)
            } else {
                val r = actions.run(action)
                diagnostics.log("action", "${action::class.simpleName} success=${r.success}")
                AssistantEvent.Local(r.message, action, r.success)
            }
            saveAssistant(conversationId, reply.text, MessageEntity.SOURCE_LOCAL)
            emit(reply)
            return@flow
        }

        // 3) cloud AI, only with explicit consent
        if (!s.cloudConsent) throw ConsentRequiredException()

        val history = dao.messages(conversationId)
            .filter { it.status == MessageEntity.STATUS_OK }
            .map { ChatTurn(it.role, it.text) }
        val turns = ContextBuilder.bound(history, maxTurns = s.historyTurns.coerceIn(2, 40), maxChars = MAX_CONTEXT_CHARS)
        val request = ChatRequest(turns, s.responseStyle, s.language, mode, s.selectedModel, s.timeoutSec, s.customRules.trim().take(Persona.MAX_RULES_CHARS))

        val start = now()
        var firstTokenAt: Long? = null
        val sb = StringBuilder()
        var usage: Usage? = null
        try {
            streamWithRetry(request, s.maxRetries).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> {
                        if (firstTokenAt == null) {
                            firstTokenAt = now()
                            stats.recordFirstToken(firstTokenAt!! - start)
                        }
                        sb.append(ev.text)
                        if (s.streaming) emit(AssistantEvent.Delta(ev.text))
                    }
                    is ChatEvent.Done -> usage = ev.usage
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (sb.isNotEmpty()) saveAssistant(conversationId, sb.toString(), MessageEntity.SOURCE_AI, MessageEntity.STATUS_CANCELLED)
            }
            throw e
        } catch (e: ProviderException) {
            stats.recordError()
            diagnostics.log("ai", "failed kind=${e.kind}")
            throw e
        }

        val full = sb.toString().trim()
        if (full.isEmpty()) {
            stats.recordError()
            throw ProviderException(ErrorKind.BAD_RESPONSE, "AI ne khaali reply diya.")
        }
        if (!s.streaming) emit(AssistantEvent.Delta(full))
        val total = now() - start
        stats.recordTotal(total, usage?.totalTokens)
        saveAssistant(conversationId, full, MessageEntity.SOURCE_AI)
        emit(AssistantEvent.Done(full, firstTokenAt?.let { it - start }, total, usage?.totalTokens))
    }

    /** Bounded retries with backoff, only for retryable errors and only before any text arrived. */
    private fun streamWithRetry(request: ChatRequest, maxRetries: Int): Flow<ChatEvent> = flow {
        var attempt = 0
        while (true) {
            var emitted = false
            try {
                provider.stream(request).collect { emitted = true; emit(it) }
                return@flow
            } catch (e: ProviderException) {
                if (emitted || !e.kind.retryable || attempt >= maxRetries.coerceIn(0, 4)) throw e
                attempt++
                diagnostics.log("ai", "retry $attempt after ${e.kind}")
                delay(600L * (1L shl (attempt - 1)))
            }
        }
    }

    private suspend fun saveAssistant(
        conversationId: Long,
        text: String,
        source: String,
        status: String = MessageEntity.STATUS_OK,
    ) {
        dao.insertMessage(
            MessageEntity(conversationId = conversationId, role = ChatTurn.ASSISTANT, text = text, createdAt = now(), status = status, source = source),
        )
        touch(conversationId, null)
    }

    private suspend fun touch(conversationId: Long, firstUserText: String?) {
        val c = dao.get(conversationId) ?: return
        val title = if (firstUserText != null && c.title == "Nayi baat") firstUserText.take(40) else c.title
        dao.update(c.copy(title = title, updatedAt = now()))
    }

    private companion object {
        const val MAX_CONTEXT_CHARS = 6000
    }
}
