package com.ultron.ai.platform.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

sealed interface SttState {
    data object Idle : SttState
    data class Listening(val partial: String) : SttState
    data class Error(val message: String, val permissionProblem: Boolean = false) : SttState
}

/**
 * Thin wrapper over android.speech.SpeechRecognizer (device/Google recognizer; may use the
 * network depending on the device). Must be used from the main thread. Exposes the real
 * microphone level (from onRmsChanged) so the UI animation reflects actual input.
 */
class SttController(private val context: Context, private val diagnostics: DiagnosticsLog) {
    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state

    /** Normalised 0..1 input level; 0 whenever the microphone is not listening. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, onFinalResult: (String) -> Unit) {
        if (!isAvailable()) {
            _state.value = SttState.Error("Is device par speech recognition service available nahi hai.")
            return
        }
        cancel()
        onFinal = onFinalResult
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = SttState.Listening("")
        r.startListening(intent)
    }

    /** Finish capturing and deliver what was heard so far. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        onFinal = null
        _level.value = 0f
        if (_state.value is SttState.Listening) _state.value = SttState.Idle
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        onFinal = null
        _level.value = 0f
        _state.value = SttState.Idle
    }

    fun clearError() {
        if (_state.value is SttState.Error) _state.value = SttState.Idle
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onEndOfSpeech() { _level.value = 0f }

        override fun onRmsChanged(rmsdB: Float) {
            _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) _state.update { SttState.Listening(text) }
        }

        override fun onResults(results: Bundle?) {
            _level.value = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val cb = onFinal
            onFinal = null
            if (text.isEmpty()) {
                _state.value = SttState.Error("Kuch samajh nahi aaya. Dobara bolo.")
            } else {
                _state.value = SttState.Idle
                cb?.invoke(text)
            }
        }

        override fun onError(error: Int) {
            _level.value = 0f
            onFinal = null
            diagnostics.log("stt", "error code=$error")
            _state.value = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    SttState.Error("Microphone permission nahi hai.", permissionProblem = true)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    SttState.Error("Kuch sunai nahi diya. Dobara try karo.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    SttState.Error("Speech recognition ko network chahiye aur connection nahi mila.")
                SpeechRecognizer.ERROR_AUDIO ->
                    SttState.Error("Microphone se audio nahi mil raha (shayad koi aur app use kar rahi hai).")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    SttState.Error("Recognizer busy hai. Ek second baad try karo.")
                SpeechRecognizer.ERROR_SERVER, 11 /* SERVER_DISCONNECTED */ ->
                    SttState.Error("Speech service ne error diya. Thodi der baad try karo.")
                12 /* LANGUAGE_NOT_SUPPORTED */, 13 /* LANGUAGE_UNAVAILABLE */ ->
                    SttState.Error("Is language ke liye speech recognition available nahi hai. Language badal ke dekho.")
                10 /* TOO_MANY_REQUESTS */ ->
                    SttState.Error("Bahut zyada recognition requests. Thoda ruk ke try karo.")
                else -> SttState.Error("Speech recognition fail hua (code $error).")
            }
        }
    }
}
