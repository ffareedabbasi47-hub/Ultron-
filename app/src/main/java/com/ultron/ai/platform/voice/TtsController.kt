package com.ultron.ai.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.core.diagnostics.LatencyStats
import com.ultron.ai.data.settings.UltronSettings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class VoiceInfo(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

data class EngineInfo(val packageName: String, val label: String)

data class TtsState(
    val initializing: Boolean = false,
    val ready: Boolean = false,
    val speaking: Boolean = false,
    val engine: String? = null,
    val engines: List<EngineInfo> = emptyList(),
    val voices: List<VoiceInfo> = emptyList(),
    val activeVoice: String? = null,
    /** Non-fatal, user-visible: e.g. the saved voice is missing and the engine default is used. */
    val warning: String? = null,
    val error: String? = null,
)

/**
 * Wraps the device TextToSpeech engine. Guarantees: one speech session at a time (no overlap),
 * stale sessions are ignored, audio focus is taken/released, the saved voice is never silently
 * replaced (a missing voice produces a visible warning), and time-to-first-audio is measured
 * from the real onStart callback.
 */
class TtsController(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
    private val stats: LatencyStats,
) {
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var settings = UltronSettings()
    private var generation = 0
    private var counter = 0
    private var pending = 0
    private var ended = true
    private var requestStartMs = 0L
    private var firstAudioRecorded = true
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** (Re)create the engine. Call when the preferred engine changes or at first use. */
    fun initialize(newSettings: UltronSettings) {
        synchronized(lock) {
            settings = newSettings
            tts?.let { it.stop(); it.shutdown() }
            tts = null
            _state.update { it.copy(initializing = true, ready = false, error = null) }
        }
        // Call on the main thread. The init callback is re-posted to the main looper so it always
        // runs after `tts` below has been assigned (no race with the constructor).
        val created = TextToSpeech(context.applicationContext, { status -> main.post { onInit(status) } }, newSettings.ttsEngine)
        synchronized(lock) { tts = created }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onInit(status: Int) {
        val engine = synchronized(lock) { tts }
        if (status != TextToSpeech.SUCCESS || engine == null) {
            diagnostics.log("tts", "init failed status=$status")
            _state.update {
                it.copy(
                    initializing = false,
                    ready = false,
                    error = "Text-to-speech engine start nahi hua. Android Settings → Text-to-speech check karo.",
                )
            }
        } else {
            onEngineReady(engine)
        }
    }

    private fun onEngineReady(engine: TextToSpeech) {
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(listener)
        val engines = engine.engines.map { EngineInfo(it.name, it.label) }
        val voices = safeVoices(engine)
        _state.update {
            it.copy(initializing = false, ready = true, engine = engine.defaultEngine, engines = engines, voices = voices, error = null)
        }
        diagnostics.log("tts", "ready engine=${engine.defaultEngine} voices=${voices.size}")
        applySettings(settings)
    }

    private fun safeVoices(engine: TextToSpeech): List<VoiceInfo> = try {
        (engine.voices ?: emptySet<Voice>()).map { v ->
            VoiceInfo(
                name = v.name,
                localeTag = v.locale.toLanguageTag(),
                quality = v.quality,
                needsNetwork = v.isNetworkConnectionRequired,
                installed = !(v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ?: false),
            )
        }.sortedWith(compareBy({ it.localeTag }, { it.name }))
    } catch (e: Exception) {
        diagnostics.log("tts", "voice enumeration failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** Applies the saved voice + rate/pitch. Never swaps the voice silently. */
    fun applySettings(newSettings: UltronSettings) {
        val engine: TextToSpeech
        synchronized(lock) {
            settings = newSettings
            engine = tts ?: return
        }
        if (!_state.value.ready) return
        engine.setSpeechRate(newSettings.ttsRate)
        engine.setPitch(newSettings.ttsPitch)

        var warning: String? = null
        var active: String? = null
        val saved = newSettings.ttsVoice
        if (saved != null) {
            val voice = try { engine.voices?.firstOrNull { it.name == saved } } catch (_: Exception) { null }
            if (voice != null) {
                engine.setVoice(voice)
                active = voice.name
            } else {
                warning = "Saved voice \"$saved\" is device par ab available nahi hai. Abhi engine ki default voice use ho rahi hai. " +
                    "Voice Studio mein dusri voice chuno ya voice pack dobara install karo."
            }
        }
        if (active == null) {
            val tag = newSettings.language.speechTag
            val res = engine.setLanguage(Locale.forLanguageTag(tag))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                warning = (warning?.plus(" ") ?: "") +
                    "Is engine mein $tag ka voice data missing/unsupported hai (Android Settings → Text-to-speech se install karo)."
            }
            active = engine.voice?.name
        }
        _state.update { it.copy(warning = warning, activeVoice = active) }
    }

    /** Starts a new, exclusive speech session; anything still speaking is cut. Returns its token. */
    fun beginSpeech(requestStartMs: Long = 0L): Int = synchronized(lock) {
        generation++
        pending = 0
        ended = false
        firstAudioRecorded = requestStartMs == 0L
        this.requestStartMs = requestStartMs
        tts?.stop()
        requestFocus()
        generation
    }

    fun enqueue(token: Int, text: String) {
        val engine: TextToSpeech
        val id: String
        synchronized(lock) {
            if (token != generation || text.isBlank()) return
            engine = tts ?: return
            pending++
            id = "u-$generation-${counter++}"
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.ttsVolume) }
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val result = engine.speak(text.take(max), TextToSpeech.QUEUE_ADD, params, id)
        if (result != TextToSpeech.SUCCESS) {
            diagnostics.log("tts", "speak() returned $result")
            synchronized(lock) { pending = (pending - 1).coerceAtLeast(0) }
            _state.update { it.copy(error = "Awaaz bolne mein error aaya.") }
            maybeFinish()
        }
    }

    /** No more chunks will come for this session. Speaking state clears once the queue drains. */
    fun endSpeech(token: Int) {
        synchronized(lock) { if (token == generation) ended = true }
        maybeFinish()
    }

    fun stop() {
        synchronized(lock) {
            generation++
            pending = 0
            ended = true
            tts?.stop()
        }
        abandonFocus()
        _state.update { it.copy(speaking = false) }
    }

    fun preview(sample: String) {
        val t = beginSpeech(0L)
        enqueue(t, sample)
        endSpeech(t)
    }

    fun shutdown() {
        stop()
        synchronized(lock) { tts?.shutdown(); tts = null }
        _state.update { TtsState() }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val record: Long?
            synchronized(lock) {
                record = if (!firstAudioRecorded && utteranceId?.startsWith("u-$generation-") == true) {
                    firstAudioRecorded = true
                    System.currentTimeMillis() - requestStartMs
                } else null
            }
            record?.let { stats.recordFirstAudio(it) }
            _state.update { it.copy(speaking = true) }
        }

        override fun onDone(utteranceId: String?) = utteranceFinished()

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            diagnostics.log("tts", "utterance error")
            _state.update { it.copy(error = "Awaaz bolne mein error aaya.") }
            utteranceFinished()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            diagnostics.log("tts", "utterance error code=$errorCode")
            _state.update { it.copy(error = "Awaaz bolne mein error aaya (code $errorCode).") }
            utteranceFinished()
        }
    }

    private fun utteranceFinished() {
        synchronized(lock) { pending = (pending - 1).coerceAtLeast(0) }
        maybeFinish()
    }

    private fun maybeFinish() {
        val done = synchronized(lock) { ended && pending == 0 }
        if (done) {
            _state.update { it.copy(speaking = false) }
            abandonFocus()
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
