package com.ultron.ai.voice

import com.ultron.ai.domain.live.LiveState
import kotlinx.coroutines.flow.StateFlow

/**
 * Provider-neutral voice pipeline state (what the rest of the app and the tests talk about). The detailed
 * [LiveState] of the live engine maps onto it; the detailed state stays available for the UI.
 */
enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, PAUSED, ERROR }

object VoiceStates {
    fun of(s: LiveState): VoiceState = when (s) {
        LiveState.STOPPED, LiveState.STARTING, LiveState.SLEEP -> VoiceState.IDLE
        LiveState.LISTENING, LiveState.USER_SPEECH -> VoiceState.LISTENING
        LiveState.THINKING -> VoiceState.PROCESSING
        LiveState.SPEAKING -> VoiceState.SPEAKING
        LiveState.PAUSED -> VoiceState.PAUSED
        LiveState.PERMISSION_REQUIRED, LiveState.AUDIO_ERROR -> VoiceState.ERROR
    }
}

/** One owner of a spoken conversation. Implemented by the live engine; UI and services depend only on this. */
interface VoiceSessionController {
    val voiceState: StateFlow<VoiceState>
    val isActive: Boolean
    fun start()
    fun pause()
    fun stop()
}

/** Detects a wake phrase in already-captured utterances. Replaceable (template matcher today, neural engine later). */
interface WakeWordEngine {
    /** True when the engine can run at all (e.g. enough enrolment recordings). */
    val ready: Boolean
    /** Score <= threshold means a match for template matchers; engines may map their own score into this shape. */
    data class Detection(val score: Float, val threshold: Float) { val match get() = score <= threshold }
    fun detect(pcm16k: ShortArray, sensitivity: Float): Detection?
}

/** Speech to text. Partial results are optional; an engine that cannot stream just calls onFinal. */
interface SpeechRecognizer {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object NoSpeech : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
    }
    suspend fun transcribe(pcm16k: ShortArray): Result
}

/** Text to speech. One speech "token" = one reply; chunks are queued in order and the whole reply can be stopped. */
interface SpeechSynthesizer {
    fun beginSpeech(requestStartMs: Long = 0L): Int
    fun enqueue(token: Int, text: String)
    fun endSpeech(token: Int)
    fun stop()
    fun isActive(): Boolean
}

/** Audio focus and output routing for a voice session. */
interface AudioSessionManager {
    enum class Route { SPEAKER, EARPIECE, WIRED, BLUETOOTH, USB, UNKNOWN }
    enum class FocusEvent { LOST_PERMANENT, LOST_TRANSIENT, LOST_CAN_DUCK, REGAINED }

    val route: StateFlow<Route>
    /** Takes audio focus for a voice session. Returns false when the system refuses it. */
    fun acquire(onFocus: (FocusEvent) -> Unit): Boolean
    fun release()
}
