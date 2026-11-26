package com.ultron.ai.data.ai

import com.ultron.ai.AssistantState
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.SentenceChunker
import com.ultron.ai.platform.voice.SttController
import com.ultron.ai.platform.voice.SttState
import com.ultron.ai.platform.voice.TtsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TurnError(val kind: ErrorKind?, val message: String)

data class TurnState(
    val busy: Boolean = false,
    /** Text streamed so far for the in-flight reply (not yet persisted). */
    val streaming: String = "",
    val error: TurnError? = null,
    val needsConsent: Boolean = false,
    val lastUserText: String? = null,
    val conversationId: Long? = null,
)

/**
 * App-wide owner of the single in-flight assistant turn. Chat and Live both go through it, which
 * guarantees: only one turn at a time (a new one cancels the old = no stale replies), one TTS
 * session at a time (no overlapping audio), and one place that derives the real [AssistantState].
 */
class TurnController(
    private val engine: AssistantEngine,
    private val tts: TtsController,
    private val stt: SttController,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val assistantState: MutableStateFlow<AssistantState>,
) {
    private val _state = MutableStateFlow(TurnState())
    val state: StateFlow<TurnState> = _state
    private var job: Job? = null

    init {
        scope.launch {
            combine(_state, tts.state, stt.state) { turn, speech, listen ->
                when {
                    listen is SttState.Listening -> AssistantState.LISTENING
                    speech.speaking -> AssistantState.SPEAKING
                    turn.busy -> AssistantState.THINKING
                    turn.error?.kind == ErrorKind.OFFLINE -> AssistantState.OFFLINE
                    turn.error != null || listen is SttState.Error -> AssistantState.ERROR
                    else -> AssistantState.IDLE
                }
            }.collect { assistantState.value = it }
        }
    }

    fun submit(conversationId: Long, text: String, mode: ChatMode, speak: Boolean, persistUser: Boolean = true) {
        job?.cancel() // stale-response prevention: the newest turn always wins
        tts.stop()
        val started = System.currentTimeMillis()
        _state.value = TurnState(busy = true, lastUserText = text, conversationId = conversationId)
        job = scope.launch {
            val chunker = SentenceChunker()
            var token: Int? = null
            fun speakChunk(chunk: String) {
                if (!speak) return
                val t = token ?: tts.beginSpeech(started).also { token = it }
                tts.enqueue(t, chunk)
            }
            try {
                engine.respond(conversationId, text, mode, persistUser).collect { ev ->
                    when (ev) {
                        is AssistantEvent.Local -> {
                            _state.update { it.copy(streaming = "") }
                            speakChunk(ev.text)
                        }
                        is AssistantEvent.Delta -> {
                            _state.update { it.copy(streaming = it.streaming + ev.text) }
                            chunker.feed(ev.text).forEach(::speakChunk)
                        }
                        is AssistantEvent.Done -> chunker.flush()?.let(::speakChunk)
                    }
                }
                token?.let { tts.endSpeech(it) }
                _state.update { it.copy(busy = false, streaming = "") }
            } catch (e: CancellationException) {
                token?.let { tts.endSpeech(it) }
                _state.update { it.copy(busy = false, streaming = "") }
                throw e
            } catch (e: ConsentRequiredException) {
                _state.update { it.copy(busy = false, streaming = "", needsConsent = true) }
            } catch (e: ProviderException) {
                chunker.reset()
                token?.let { tts.endSpeech(it) }
                _state.update { it.copy(busy = false, streaming = "", error = TurnError(e.kind, e.message ?: "Error")) }
            } catch (e: Exception) {
                diagnostics.log("turn", "unexpected ${e.javaClass.simpleName}")
                token?.let { tts.endSpeech(it) }
                _state.update { it.copy(busy = false, streaming = "", error = TurnError(null, "Kuch galat ho gaya. Dobara try karo.")) }
            }
        }
    }

    /** Re-run the last user text without saving it twice. */
    fun retry(mode: ChatMode, speak: Boolean) {
        val s = _state.value
        val id = s.conversationId ?: return
        val text = s.lastUserText ?: return
        submit(id, text, mode, speak, persistUser = false)
    }

    /** User pressed Stop: cancel the request (HTTP stream included) and all speech. */
    fun cancel() {
        job?.cancel()
        job = null
        tts.stop()
        _state.update { it.copy(busy = false, streaming = "") }
    }

    fun dismissError() = _state.update { it.copy(error = null, needsConsent = false) }

    /** Consent granted: persist the choice and re-send what was pending. */
    suspend fun grantConsentAndRetry(mode: ChatMode, speak: Boolean) {
        settings.update { it.copy(cloudConsent = true) }
        _state.update { it.copy(needsConsent = false) }
        retry(mode, speak)
    }

    suspend fun speakEnabled(): Boolean = settings.settings.first().speakReplies
}
