package com.ultron.ai.platform.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.AssistantEngine
import com.ultron.ai.data.ai.ChatMode
import com.ultron.ai.data.ai.TurnController
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.domain.live.LiveState
import com.ultron.ai.domain.live.LiveTransitions
import com.ultron.ai.domain.live.SleepClock
import com.ultron.ai.domain.live.TtsEchoGate
import com.ultron.ai.domain.live.VadConfig
import com.ultron.ai.domain.live.VadEvent
import com.ultron.ai.domain.live.VadSegmenter
import com.ultron.ai.domain.live.WakeWord
import com.ultron.ai.platform.voice.CloudVoice
import com.ultron.ai.platform.voice.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the diagnostics screen shows for the session itself (all values are real, measured or configured). */
data class LiveDiag(
    val state: LiveState = LiveState.STOPPED,
    val vadSpeech: Boolean = false,
    val levelDb: Float = -90f,
    val noiseFloorDb: Float = -60f,
    val ttsActive: Boolean = false,
    val sleepInMs: Long? = null,
    val utterances: Int = 0,
    val discardedAsEcho: Int = 0,
    val rejectedShort: Int = 0,
    val lastTranscribeMs: Long? = null,
    val lastReplyStartMs: Long? = null,
    val commMode: Boolean = false,
    val lastError: String? = null,
    val note: String? = null,
)

/**
 * The single owner of a live conversation. The microphone ([MicCapture]) stays open for the whole session;
 * this class only decides what each piece of audio MEANS: user speech, ULTRON's own echo, noise, or a wake phrase.
 * Capture lifetime is independent of STT, the AI request and TTS, and of any Activity or Composable.
 */
class LiveSessionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    val mic: MicCapture,
    private val transcriber: GeminiTranscriber,
    private val turns: TurnController,
    private val assistant: AssistantEngine,
    private val tts: TtsController,
    private val cloudVoice: CloudVoice,
    private val diagnostics: DiagnosticsLog,
    private val broker: com.ultron.ai.platform.actions.ConfirmationBroker? = null,
) {
    private val _state = MutableStateFlow(LiveState.STOPPED)
    val state: StateFlow<LiveState> = _state
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _heard = MutableStateFlow("")
    /** Last thing ULTRON understood from you (shown in the UI; never stored by this class). */
    val heard: StateFlow<String> = _heard
    private val _diag = MutableStateFlow(LiveDiag())
    val diag: StateFlow<LiveDiag> = _diag
    private val _message = MutableStateFlow<String?>(null)
    /** Short user-visible reason for PAUSED / AUDIO_ERROR / PERMISSION_REQUIRED / transcription problems. */
    val message: StateFlow<String?> = _message

    private val stateLock = Any()
    private var cfg = UltronSettings()
    private var vad = VadSegmenter()
    private val gate = TtsEchoGate()
    private var clock = SleepClock(0)
    private val utterances = Channel<Pair<ShortArray, Long>>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var processor: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    private var conversationId: Long? = null
    private var speechStartMs = 0L
    @Volatile private var verifiedSpeechAt = false
    private var counts = LiveDiag()
    private var savedMode: Int? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    private fun now() = SystemClock.elapsedRealtime()

    private fun move(to: LiveState): Boolean = synchronized(stateLock) {
        val from = _state.value
        if (!LiveTransitions.canMove(from, to)) { diagnostics.log("live", "blocked $from->$to"); return false }
        if (from != to) _state.value = to
        publishDiag()
        true
    }

    private fun publishDiag(extra: (LiveDiag) -> LiveDiag = { it }) {
        counts = extra(counts)
        _diag.value = counts.copy(
            state = _state.value, vadSpeech = vad.isSpeech, levelDb = vad.lastLevelDb, noiseFloorDb = vad.noiseFloorDb,
            ttsActive = tts.isActive(), sleepInMs = clock.remainingMs(now()),
        )
    }

    val isActive: Boolean get() = _state.value != LiveState.STOPPED

    /** Start (or resume from PAUSED/SLEEP/ERROR). Call from a user-visible action, with the foreground service running. */
    fun start() {
        scope.launch {
            val s = settings.settings.first()
            cfg = s
            synchronized(stateLock) {
                when (_state.value) {
                    LiveState.STOPPED, LiveState.AUDIO_ERROR, LiveState.PERMISSION_REQUIRED, LiveState.PAUSED -> _state.value = LiveState.STARTING
                    LiveState.SLEEP -> if (!mic.isRunning) _state.value = LiveState.STARTING else { wakeInternal(); return@launch }
                    else -> return@launch // already running: never open a second recorder
                }
                Unit
            }
            _message.value = null
            vad = VadSegmenter(vadConfig(s))
            clock = SleepClock(s.sleepTimeout.millis).also { it.reset(now()) }
            applyAudioRoute(s)
            val ok = mic.start(::onFrame, ::onFatal, ::onSilenced)
            if (!ok) { publishDiag(); return@launch } // onFatal already set the state
            move(LiveState.LISTENING)
            startJobs()
            publishDiag { it.copy(commMode = s.fullDuplex, note = if (s.fullDuplex) "Full-duplex: echo canceller par bharosa (device-dependent)" else "Safe mode: ULTRON bolte waqt aapki awaaz ignore hoti hai") }
        }
    }

    private fun vadConfig(s: UltronSettings) = VadConfig(
        endPauseMs = s.endPauseMs,
        startMarginDb = if (s.reduceMediaInterference) 14f else 11f,
        startFrames = if (s.reduceMediaInterference) 4 else 3,
    )

    private fun startJobs() {
        processor?.cancel(); ticker?.cancel(); watcher?.cancel()
        processor = scope.launch(Dispatchers.Default) { for ((pcm, startedAt) in utterances) handleUtterance(pcm, startedAt) }
        ticker = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                val st = _state.value
                val busy = st == LiveState.THINKING || st == LiveState.SPEAKING || st == LiveState.USER_SPEECH || tts.isActive()
                val spoke = verifiedSpeechAt.also { verifiedSpeechAt = false }
                if (st == LiveState.LISTENING && clock.tick(now(), spoke, busy)) goToSleep() else if (st != LiveState.LISTENING) clock.tick(now(), spoke, true)
                publishDiag()
            }
        }
        // Mirror real TTS playback into the echo gate (monotonic clock).
        watcher = scope.launch {
            var was = false
            tts.state.collect { t ->
                if (t.speaking && !was) gate.onSpeechStart() else if (!t.speaking && was) gate.onSpeechEnd(now())
                was = t.speaking
            }
        }
    }

    // ---------- audio thread ----------
    private fun onFrame(frame: ShortArray) {
        val st = _state.value
        if (st == LiveState.PAUSED || st == LiveState.STOPPED) return
        val duplex = cfg.fullDuplex
        val speakingNow = gate.assistantSpeaking
        // Level meter for the UI: real input level, only while it is meaningful.
        val events = vad.feed(frame, assistantSpeaking = speakingNow && duplex)
        _level.value = ((vad.lastLevelDb + 60f) / 45f).coerceIn(0f, 1f)
        for (e in events) when (e) {
            VadEvent.SpeechStart -> {
                if (st == LiveState.SLEEP) { speechStartMs = now(); continue }
                if (speakingNow && !duplex) { speechStartMs = now(); continue } // ULTRON's own voice: not the user
                speechStartMs = now()
                if (st == LiveState.LISTENING || st == LiveState.THINKING || st == LiveState.SPEAKING) move(LiveState.USER_SPEECH)
            }
            is VadEvent.Utterance -> {
                val aecEffective = duplex && mic.info.value.aecEnabled
                if (gate.shouldDiscard(speechStartMs, now(), aecEffective)) {
                    publishDiag { it.copy(discardedAsEcho = it.discardedAsEcho + 1) }
                    restoreAfterDiscard()
                } else {
                    publishDiag { it.copy(utterances = it.utterances + 1) }
                    if (broker?.isPending == true) {
                        // A "karun? haan/nahi" question is open: this utterance is the ANSWER, not a new command.
                        val pcm = e.pcm
                        scope.launch(Dispatchers.Default) {
                            val tr = transcriber.transcribe(pcm, MicCapture.SAMPLE_RATE)
                            if (tr is Transcript.Text) { verifiedSpeechAt = true; broker.answerFromText(tr.text) }
                        }
                        restoreAfterDiscard()
                    } else {
                        // Barge-in: a genuine new utterance cancels the reply that is being thought/spoken.
                        if (turns.state.value.busy || tts.isActive()) scope.launch { turns.cancel() }
                        utterances.trySend(e.pcm to speechStartMs)
                    }
                }
            }
            VadEvent.Rejected -> { publishDiag { it.copy(rejectedShort = it.rejectedShort + 1) }; restoreAfterDiscard() }
        }
    }

    private fun restoreAfterDiscard() {
        if (_state.value == LiveState.USER_SPEECH) move(if (tts.isActive()) LiveState.SPEAKING else if (turns.state.value.busy) LiveState.THINKING else LiveState.LISTENING)
    }

    // ---------- utterance -> text -> reply ----------
    private suspend fun handleUtterance(pcm: ShortArray, startedAt: Long) {
        val wasAsleep = _state.value == LiveState.SLEEP
        val durMs = pcm.size * 1000L / MicCapture.SAMPLE_RATE
        if (wasAsleep && (!cfg.wakeCloudCheck || durMs > 3500)) return // asleep: only short phrases, only if the user opted in
        if (!wasAsleep) move(LiveState.THINKING)
        val t0 = now()
        val tr = transcriber.transcribe(pcm, MicCapture.SAMPLE_RATE)
        publishDiag { it.copy(lastTranscribeMs = now() - t0) }
        val text = when (tr) {
            is Transcript.Text -> tr.text
            Transcript.NoSpeech -> { if (!wasAsleep) move(LiveState.LISTENING); return }
            is Transcript.Failed -> { _message.value = tr.message; if (!wasAsleep) move(LiveState.LISTENING); return }
        }
        verifiedSpeechAt = true
        var command = text
        if (wasAsleep) {
            val rest = WakeWord.match(text) ?: return
            wakeInternal()
            if (rest.isEmpty()) { speakShort("Haan. Sun raha hoon."); return }
            command = rest
        } else if (_state.value == LiveState.SLEEP) return
        _heard.value = command
        if (broker?.answerFromText(command) == true) { move(LiveState.LISTENING); clock.reset(now()); return } // answer to a pending "karun?"
        _message.value = null
        if (command.trim().lowercase() in STOP_WORDS) { turns.cancel(); move(LiveState.LISTENING); return }
        val id = conversationId ?: assistant.latestOrNew(ChatMode.CHAT).also { conversationId = it }
        val replyStart = now()
        turns.submit(id, command, ChatMode.CHAT, speak = true)
        var spokeOnce = false
        // Follow the real state of the reply until it has finished AND the speaker has gone quiet.
        while (true) {
            delay(40)
            val busy = turns.state.value.busy
            val talking = tts.isActive()
            if (talking && !spokeOnce) { spokeOnce = true; publishDiag { it.copy(lastReplyStartMs = now() - replyStart) } }
            if (_state.value == LiveState.USER_SPEECH) break // barge-in: the user is already talking again
            if (talking) move(LiveState.SPEAKING) else if (busy) move(LiveState.THINKING)
            if (!busy && !talking) break
        }
        if (turns.state.value.needsConsent) _message.value = "Cloud AI ki permission chahiye."
        turns.state.value.error?.let { _message.value = it.message }
        if (_state.value == LiveState.THINKING || _state.value == LiveState.SPEAKING) move(LiveState.LISTENING)
        clock.reset(now())
    }

    private fun speakShort(line: String) {
        val tok = tts.beginSpeech(0L); tts.enqueue(tok, line); tts.endSpeech(tok)
    }

    // ---------- lifecycle ----------
    private fun goToSleep() {
        if (!move(LiveState.SLEEP)) return
        if (!cfg.wakeCloudCheck) {
            // No wake-check opted in: nothing could ever use the audio, so the mic is released (and restarted on wake).
            mic.stop()
            diagnostics.log("live", "sleep: recorder released (no wake check enabled)")
        }
        publishDiag()
    }

    /** Leave SLEEP (notification action, Home tap, or recognised wake phrase). */
    fun wake() { scope.launch { start() } }

    private fun wakeInternal() {
        if (!mic.isRunning) { scope.launch { _state.value = LiveState.STOPPED; start() }; return }
        move(LiveState.LISTENING); clock.reset(now())
    }

    fun pause() {
        if (!isActive) return
        move(LiveState.PAUSED)
        mic.stop()
        _level.value = 0f
        publishDiag()
    }

    fun stop() {
        if (_state.value == LiveState.STOPPED) return
        synchronized(stateLock) { _state.value = LiveState.STOPPED }
        processor?.cancel(); ticker?.cancel(); watcher?.cancel()
        processor = null; ticker = null; watcher = null
        mic.stop()
        turns.cancel()
        utterances.tryReceive()
        restoreAudioRoute()
        _level.value = 0f
        _message.value = null
        vad.reset()
        publishDiag()
    }

    private fun onFatal(reason: String) {
        scope.launch {
            if (reason == "PERMISSION") { synchronized(stateLock) { _state.value = LiveState.PERMISSION_REQUIRED }; _message.value = "Microphone permission chahiye." }
            else { synchronized(stateLock) { _state.value = LiveState.AUDIO_ERROR }; _message.value = reason }
            processor?.cancel(); ticker?.cancel(); watcher?.cancel()
            mic.stop(); restoreAudioRoute(); _level.value = 0f
            publishDiag { it.copy(lastError = reason) }
        }
    }

    private fun onSilenced(silenced: Boolean) {
        scope.launch {
            if (silenced && (_state.value == LiveState.LISTENING || _state.value == LiveState.USER_SPEECH)) {
                _message.value = "Android ne mic ko abhi block kiya hai (call / privacy / dusri app)."
                publishDiag { it.copy(note = "mic silenced by system") }
            } else if (!silenced) { _message.value = null; publishDiag { it.copy(note = null) } }
        }
    }

    // ---------- audio routing (full-duplex only) ----------
    private fun applyAudioRoute(s: UltronSettings) {
        cloudVoice.communicationUsage = s.fullDuplex
        if (!s.fullDuplex) return
        try {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val external = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (!external) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = true }
                }
            }
        } catch (e: Exception) { diagnostics.log("live", "route setup ${e.javaClass.simpleName}") }
    }

    private fun restoreAudioRoute() {
        cloudVoice.communicationUsage = false
        val m = savedMode ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = false }
            audio.mode = m
        } catch (e: Exception) { diagnostics.log("live", "route restore ${e.javaClass.simpleName}") }
        savedMode = null
    }

    private companion object {
        val STOP_WORDS = setOf("stop", "ruk", "ruko", "chup", "chup ho ja", "bas", "band karo", "ruk ja")
    }
}
