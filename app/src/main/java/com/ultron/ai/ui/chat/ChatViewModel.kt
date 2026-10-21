package com.ultron.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultron.ai.AppContainer
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.db.ConversationEntity
import com.ultron.ai.data.db.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer, val mode: ChatMode) : ViewModel() {
    private val dao = c.db.conversations()

    val conversationId = MutableStateFlow<Long?>(null)
    val turn = c.turns.state

    val messages: StateFlow<List<MessageEntity>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { conversationId.value = c.engine.latestOrNew(mode) }
    }

    /** Resolves the conversation id (creating one if needed) and submits the text. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            val id = conversationId.value ?: c.engine.latestOrNew(mode).also { conversationId.value = it }
            c.turns.submit(id, t, mode, speak = c.turns.speakEnabled())
        }
    }

    fun retry() = viewModelScope.launch { c.turns.retry(mode, c.turns.speakEnabled()) }
    fun cancel() = c.turns.cancel()
    fun dismiss() = c.turns.dismissError()
    fun grantConsent() = viewModelScope.launch { c.turns.grantConsentAndRetry(mode, c.turns.speakEnabled()) }
    fun stopSpeaking() = c.tts.stop()

    fun newChat() = viewModelScope.launch {
        c.turns.cancel()
        c.turns.dismissError()
        conversationId.value = c.engine.newConversation(mode)
    }

    fun open(id: Long) {
        c.turns.cancel()
        c.turns.dismissError()
        conversationId.value = id
    }

    fun delete(id: Long) = viewModelScope.launch {
        dao.delete(id)
        if (conversationId.value == id) conversationId.value = c.engine.latestOrNew(mode)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        val conv = dao.get(id) ?: return@launch
        dao.update(conv.copy(title = title.trim().take(60).ifEmpty { conv.title }))
    }

    fun deleteAll() = viewModelScope.launch {
        c.turns.cancel()
        dao.deleteAll()
        conversationId.value = c.engine.newConversation(mode)
    }

    /** Plain-text export of one conversation for sharing. */
    suspend fun exportText(id: Long): String {
        val conv = dao.get(id)
        val sb = StringBuilder("ULTRON chat: ${conv?.title ?: ""}\n\n")
        dao.messages(id).forEach { m ->
            sb.append(if (m.role == "user") "Aap: " else "ULTRON: ").append(m.text).append("\n\n")
        }
        return sb.toString().trim()
    }
}
