package com.ultron.ai.data.ai

import com.ultron.ai.data.settings.AppLanguage
import com.ultron.ai.data.settings.ResponseStyle
import com.ultron.ai.domain.ChatTurn
import kotlinx.coroutines.flow.Flow

enum class ChatMode(val wire: String) { CHAT("chat"), STUDY("study") }

data class ChatRequest(
    val turns: List<ChatTurn>,
    val style: ResponseStyle,
    val language: AppLanguage,
    val mode: ChatMode,
    val model: String?,
    val timeoutSec: Int,
)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Done(val usage: Usage?, val model: String?) : ChatEvent
}

enum class ErrorKind(val retryable: Boolean) {
    NOT_CONFIGURED(false),
    OFFLINE(true),
    TIMEOUT(true),
    UNAUTHORIZED(false),
    RATE_LIMITED(false),
    SERVER(true),
    BAD_RESPONSE(false),
    CANCELLED(false),
}

class ProviderException(val kind: ErrorKind, message: String) : Exception(message)

/** Thrown when the user has not yet allowed sending chat text to the cloud backend. */
class ConsentRequiredException : Exception("Cloud AI consent required")

data class ProviderStatus(
    val reachable: Boolean,
    val configured: Boolean,
    val authOk: Boolean?,
    val provider: String?,
    val version: String?,
    val models: List<String>,
    val error: String?,
)

/** Provider adapter boundary: UI and business logic never depend on a specific vendor. */
interface AIProvider {
    suspend fun status(): ProviderStatus
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
