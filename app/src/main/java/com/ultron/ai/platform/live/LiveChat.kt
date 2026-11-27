package com.ultron.ai.platform.live

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.AssistantEngine
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.ai.TurnController
import com.ultron.ai.data.db.ConversationDao
import com.ultron.ai.data.db.MessageEntity
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.live.LiveTurnAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Gemini Live is ULTRON's one primary AI. This class connects the Chat screen to the SAME [LiveCall] session that voice uses:
 *  - typed text -> LiveCall.sendText (Live API realtimeInput text), never the normal chat model;
 *  - every Live transcript (spoken or typed) is saved once into the chat history -> one visible conversation;
 *  - a fresh session gets the saved history back as context; an expired session is reported, not pretended.
 * If Live cannot work, an honest error is shown. There is NO silent fallback to another AI.
 */
class LiveChat(
    private val scope: CoroutineScope,
    private val call: LiveCall,
    private val settings: SettingsRepository,
    private val dao: ConversationDao,
    private val engine: AssistantEngine,
    private val turns: TurnController,
    private val diagnostics: DiagnosticsLog,
    private val voiceActive: () -> Boolean,
) : LiveCall.Sink {
    @Volatile private var conversationId: Long? = null
    private val assembler = LiveTurnAssembler()
    private var watchdog: Job? = null

    init { call.sink = this }

    /** The chat conversation Live transcripts are written into (set by the Chat screen). */
    fun bind(id: Long) { conversationId = id }

    suspend fun send(id: Long, text: String, speak: Boolean) {
        conversationId = id
        val s = settings.settings.first()
        if (!s.liveCall) { turns.liveError("Live AI band hai (Settings → Live call). Typed message Live ko nahi bheja."); return }
        if (!s.cloudConsent || settings.geminiKey() == null) { turns.liveError("Gemini Live ke liye Cloud AI allow aur Gemini key chahiye. Message bheja nahi gaya."); return }
        saveMessage(id, "user", text, firstUser = text)
        turns.liveBusy(true)
        if (!call.isActive) {
            diagnostics.log("livechat", "no session: starting text session")
            call.start(object : LiveCall.Listener {
                override fun onReady() {}
                override fun onSpeaking(speaking: Boolean) {}
                override fun onHeard(text: String) {}
                override fun onSaid(text: String) {}
                override fun onFailed(reason: String, retryable: Boolean) { turns.liveError(reason) }
                override fun onEnded() {}
            }, com.ultron.ai.domain.Persona.systemPrompt(
                com.ultron.ai.BuildConfig.CREATOR_NAME, s.responseStyle.wire, s.language.wire, "chat", s.customRules,
            ), s.cloudVoiceName, s.ttsVolume, s.fullDuplex)
        }
        var waited = 0
        while (!call.isReady && call.isActive && waited < 12_000) { delay(100); waited += 100 }
        if (!call.isReady) { if (!call.isActive) return /* onFailed already showed the reason */; turns.liveError("Gemini Live se connect nahi ho paya. Message bheja nahi gaya."); return }
        call.muteAudio = !speak && !voiceActive()
        assembler.reset()
        if (!call.sendText(text)) { turns.liveError("Live session toot gayi, message bheja nahi gaya. Dobara bhejo."); return }
        diagnostics.log("livechat", "typed text sent to live session")
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(40_000)
            if (turns.state.value.busy) turns.liveError("Gemini Live ne 40 second me jawab nahi diya.")
        }
    }

    /** A local note (e.g. web sources) shown in the chat; stored once, marked local. */
    fun addNote(text: String) {
        scope.launch {
            val cid = conversationId ?: engine.latestOrNew(ChatMode.CHAT).also { conversationId = it }
            dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", text = text, createdAt = System.currentTimeMillis(), source = MessageEntity.SOURCE_LOCAL))
        }
    }

    override fun onEvent(heard: String?, said: String?, turnComplete: Boolean, interrupted: Boolean) {
        val out = synchronized(assembler) { assembler.feed(heard, said, turnComplete, interrupted) }
        val id = conversationId
        scope.launch {
            val cid = id ?: engine.latestOrNew(ChatMode.CHAT).also { conversationId = it }
            out.forEach { saveMessage(cid, it.role, it.text, firstUser = if (it.role == "user") it.text else null) }
            if (turnComplete || interrupted) { watchdog?.cancel(); turns.liveBusy(false) }
        }
    }

    override fun onDown(reason: String) {
        watchdog?.cancel()
        turns.liveError(reason)
    }

    override suspend fun history(): List<Pair<String, String>> {
        val id = conversationId ?: return emptyList()
        return dao.messages(id).filter { it.status == MessageEntity.STATUS_OK && it.text.isNotBlank() }.takeLast(20)
            .map { (if (it.role == "user") "user" else "model") to it.text.take(1500) }
    }

    private suspend fun saveMessage(id: Long, role: String, text: String, firstUser: String?) {
        dao.insertMessage(MessageEntity(conversationId = id, role = role, text = text, createdAt = System.currentTimeMillis()))
        engine.touchConversation(id, firstUser)
    }
}
