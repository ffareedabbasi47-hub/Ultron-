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
import com.ultron.ai.domain.wake.WakeCooldown
import com.ultron.ai.domain.wake.WakePhrase
import com.ultron.ai.platform.voice.CloudVoice
import com.ultron.ai.platform.voice.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val personal: PersonalWake? = null,
    private val liveStt: LiveTranscriber? = null,
    private val call: LiveCall? = null,
    private val audioSession: com.ultron.ai.voice.AudioSessionManager? = null,
) : com.ultron.ai.voice.VoiceSessionController {

    /** True while a phone-call style (Gemini Live) conversation owns the session. */
    @Volatile private var inCall = false
    /** Set after a call failed once: the rest of this session uses the normal pipeline. */
    @Volatile private var forceNormal = false
    /** True while the current utterance is being streamed to the live transcriber. */
    @Volatile private var streaming = false
    private val _state = MutableStateFlow(LiveState.STOPPED)
    val state: StateFlow<LiveState> = _state
    override val voiceState: StateFlow<com.ultron.ai.voice.VoiceState> =
        _state.map { com.ultron.ai.voice.VoiceStates.of(it) }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.ultron.ai.voice.VoiceState.IDLE)
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

    private val _wake = MutableSharedFlow<WakePhrase>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits once per accepted wake phrase (drives the edge-light activation animation). */
    val wakeEvents: SharedFlow<WakePhrase> = _wake
    private val wakeCooldown = WakeCooldown()
    /** A message that must survive the start sequence (e.g. "call failed, using the normal mode"). */
    @Volatile private var carryMessage: String? = null

    private val stateLock = Any()
    private var cfg = UltronSettings()
    private var vad = VadSegmenter()
    private val gate = TtsEchoGate()
    private var clock = SleepClock(0)
    private val utterances = Channel<Triple<ShortArray, Long, kotlinx.coroutines.Deferred<String?>?>>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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

    override val isActive: Boolean get() = _state.value != LiveState.STOPPED

    /**
     * Start (or resume from PAUSED/SLEEP/ERROR). Call from a user-visible action, with the foreground service running.
     * The STARTING claim is taken SYNCHRONOUSLY: a second tap / intent a millisecond later sees STARTING and returns, so
     * two recorders can never be opened, and observers never see a stale STOPPED between "start requested" and "starting".
     */
    override fun start() {
        synchronized(stateLock) {
            when (_state.value) {
                LiveState.STOPPED, LiveState.AUDIO_ERROR, LiveState.PERMISSION_REQUIRED, LiveState.PAUSED -> _state.value = LiveState.STARTING
                LiveState.SLEEP -> if (!mic.isRunning) _state.value = LiveState.STARTING else { wakeInternal(); return }
                else -> return // already running / starting: never open a second recorder
            }
            Unit
        }
        scope.launch { runStart() }
    }

    private suspend fun runStart() {
        val s = settings.settings.first()
        cfg = s
        if (_state.value != LiveState.STARTING) return // Stop/Pause arrived while settings were loading
        _message.value = carryMessage.also { carryMessage = null }
        vad = VadSegmenter(vadConfig(s))
        clock = SleepClock(s.sleepTimeout.millis).also { it.reset(now()) }
        applyAudioRoute(s)
        val cloudReady = s.cloudConsent && settings.geminiKey() != null
        // Armed = the mic only feeds the on-phone wake matcher. It is used when there is no cloud permission/key at all
        // (nothing could be transcribed anyway) or when the user chose "wake first". Phone-call mode streams ALL audio, so it never starts armed.
        val armed = s.wakePersonal && personal?.ready == true && (!cloudReady || s.wakeFirst)
        val useCall = call != null && s.liveCall && cloudReady && !forceNormal && !armed
        val ok = mic.start(if (useCall) ::onCallFrame else ::onFrame, ::onFatal, ::onSilenced)
        if (!ok) { publishDiag(); return } // onFatal already set the state
        if (_state.value != LiveState.STARTING) {
            // Stopped while the recorder was opening: release it again, leave nothing behind.
            mic.stop(); restoreAudioRoute(); return
        }
        move(LiveState.LISTENING)
        if (useCall) { beginCall(s); return }
        startJobs()
        // Offline wake-only: no Gemini key / cloud consent, but the user's own wake recordings exist. Nothing is uploaded;
        // the mic only feeds the on-phone wake matcher until the wake phrase is heard.
        if (armed) {
            _message.value = if (cloudReady) null else "Cloud AI/Gemini key off hai: sirf offline wake word chal raha hai (kuch upload nahi hota)."
            move(LiveState.SLEEP)
            publishDiag { it.copy(commMode = s.fullDuplex, note = if (cloudReady) "Wake-first: wake word ka intezar" else "Offline wake-only mode") }
            return
        }
        liveStt?.connect() // pre-connect so the first sentence is already fast
        publishDiag { it.copy(commMode = s.fullDuplex, note = if (s.fullDuplex) "Full-duplex: echo canceller par bharosa (device-dependent)" else "Safe mode: ULTRON bolte waqt aapki awaaz ignore hoti hai") }
    }

    // ---------- phone-call mode ----------
    private fun beginCall(s: UltronSettings) {
        val c = call ?: return
        inCall = true
        acquireFocus() // a call takes audio focus like any call; the normal listening mode never holds it (it would pause the user's music)
        clock = SleepClock(0)
        val prompt = com.ultron.ai.domain.Persona.systemPrompt(
            com.ultron.ai.BuildConfig.CREATOR_NAME, s.responseStyle.wire, s.language.wire, "chat", s.customRules,
        ) + " This is a live phone-style voice call: answer in one or two short spoken sentences, start immediately, deep calm menacing delivery with audible measured breaths between phrases, no lists or markdown."
        c.start(object : LiveCall.Listener {
            override fun onReady() { publishDiag { it.copy(note = "Live call: connected") } }
            override fun onSpeaking(speaking: Boolean) { move(if (speaking) LiveState.SPEAKING else LiveState.LISTENING) }
            override fun onHeard(text: String) { _heard.value = text; if (_state.value == LiveState.LISTENING) move(LiveState.USER_SPEECH) }
            override fun onSaid(text: String) {}
            override fun onFailed(reason: String, retryable: Boolean) { callFailed(reason) }
            override fun onEnded() { stop() }
        }, prompt, s.cloudVoiceName, s.ttsVolume, s.fullDuplex)
    }

    /** Mic frames go straight to the call. While ULTRON speaks they are muted unless the echo canceller is really on. */
    private fun onCallFrame(frame: ShortArray) {
        val st = _state.value
        if (st == LiveState.PAUSED || st == LiveState.STOPPED) return
        var e = 0.0; for (v in frame) e += v.toDouble() * v
        val db = (10 * kotlin.math.log10(e / frame.size.coerceAtLeast(1) / (32768.0 * 32768.0) + 1e-10)).toFloat()
        _level.value = ((db + 60f) / 45f).coerceIn(0f, 1f)
        val aec = cfg.fullDuplex && mic.info.value.aecEnabled
        if (st == LiveState.SPEAKING && !aec) return
        call?.sendMic(frame)
    }

    private fun callFailed(reason: String) {
        scope.launch {
            if (!inCall) return@launch
            inCall = false
            forceNormal = true
            mic.stop()
            // PAUSED (not STOPPED): the foreground service shuts itself down on STOPPED, which would kill the fallback session.
            synchronized(stateLock) { _state.value = LiveState.PAUSED }
            carryMessage = "$reason Ab purana tareeka (sun kar jawab) chalu kar raha hoon."
            start()
        }
    }

    private fun vadConfig(s: UltronSettings) = VadConfig(
        endPauseMs = s.endPauseMs,
        startMarginDb = if (s.reduceMediaInterference) 14f else 11f,
        startFrames = if (s.reduceMediaInterference) 4 else 3,
    )

    private fun startJobs() {
        processor?.cancel(); ticker?.cancel(); watcher?.cancel()
        processor = scope.launch(Dispatchers.Default) { for ((pcm, startedAt, stt) in utterances) handleUtterance(pcm, startedAt, stt) }
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
        val wasSpeech = vad.isSpeech
        val events = vad.feed(frame, assistantSpeaking = speakingNow && duplex)
        _level.value = ((vad.lastLevelDb + 60f) / 45f).coerceIn(0f, 1f)
        for (e in events) when (e) {
            VadEvent.SpeechStart -> {
                if (st == LiveState.SLEEP) { speechStartMs = now(); continue }
                if (speakingNow && !duplex) { speechStartMs = now(); continue } // ULTRON's own voice: not the user
                speechStartMs = now()
                streaming = liveStt?.begin(vad.utteranceSoFar()) == true
                if (st == LiveState.LISTENING || st == LiveState.THINKING || st == LiveState.SPEAKING) move(LiveState.USER_SPEECH)
            }
            is VadEvent.Utterance -> {
                val aecEffective = duplex && mic.info.value.aecEnabled
                if (gate.shouldDiscard(speechStartMs, now(), aecEffective)) {
                    publishDiag { it.copy(discardedAsEcho = it.discardedAsEcho + 1) }
                    if (streaming) { liveStt?.cancel(); streaming = false }
                    restoreAfterDiscard()
                } else {
                    publishDiag { it.copy(utterances = it.utterances + 1) }
                    if (broker?.isPending == true) {
                        // A "karun? haan/nahi" question is open: this utterance is the ANSWER, not a new command.
                        val pcm = e.pcm
                        if (streaming) { liveStt?.cancel(); streaming = false }
                        scope.launch(Dispatchers.Default) {
                            val tr = transcriber.transcribe(pcm, MicCapture.SAMPLE_RATE)
                            if (tr is Transcript.Text) { verifiedSpeechAt = true; broker.answerFromText(tr.text) }
                        }
                        restoreAfterDiscard()
                    } else {
                        // Barge-in: a genuine new utterance cancels the reply that is being thought/spoken.
                        if (turns.state.value.busy || tts.isActive()) scope.launch { turns.cancel() }
                        val d = if (streaming) liveStt?.end() else null
                        streaming = false
                        utterances.trySend(Triple(e.pcm, speechStartMs, d))
                    }
                }
            }
            VadEvent.Rejected -> { if (streaming) { liveStt?.cancel(); streaming = false }; publishDiag { it.copy(rejectedShort = it.rejectedShort + 1) }; restoreAfterDiscard() }
        }
        if (wasSpeech && streaming && events.none { it is VadEvent.Utterance || it === VadEvent.Rejected }) liveStt?.push(frame)
    }

    private fun streamTranscript(s: String?): Transcript? = when {
        s == null -> null
        s.isBlank() -> Transcript.NoSpeech
        // Local commands ("youtube kholo") are matched in Roman Hinglish: let the one-shot path romanise short Devanagari.
        com.ultron.ai.domain.live.LiveWire.hasDevanagari(s) && s.trim().split(Regex("\\s+")).size <= 6 -> null
        else -> Transcript.Text(s.trim())
    }

    private fun restoreAfterDiscard() {
        if (_state.value == LiveState.USER_SPEECH) move(if (tts.isActive()) LiveState.SPEAKING else if (turns.state.value.busy) LiveState.THINKING else LiveState.LISTENING)
    }

    // ---------- utterance -> text -> reply ----------
    private suspend fun handleUtterance(pcm: ShortArray, startedAt: Long, stt: kotlinx.coroutines.Deferred<String?>? = null) {
        val wasAsleep = _state.value == LiveState.SLEEP
        val durMs = pcm.size * 1000L / MicCapture.SAMPLE_RATE
        var wokeLocally = false
        var localPhrase: WakePhrase? = null
        if (wasAsleep) {
            val pw = personal
            if (cfg.wakePersonal && pw != null && pw.ready) {
                // ULTRON's own voice (even with a working echo canceller) can never wake ULTRON.
                if (gate.shouldDiscard(startedAt, now(), aecEffective = false)) { publishDiag { it.copy(discardedAsEcho = it.discardedAsEcho + 1) }; return }
                // Long speech (TV, conversation) is skipped before any feature extraction: battery.
                val d = if (durMs in 250..pw.maxWakeUtteranceMs()) pw.decide(pcm, cfg.wakeSensitivity) else null
                if (d != null) publishDiag { it.copy(note = "wake %s %.2f / %.2f".format(d.note, d.score, d.threshold)) }
                if (d?.phrase != null && wakeCooldown.allow(now())) {
                    wokeLocally = true
                    localPhrase = d.phrase
                    verifiedSpeechAt = true
                    wakeInternal()
                    _wake.tryEmit(d.phrase)
                    if (durMs < 2400) { greet(d.phrase); return }
                } else if (!cfg.wakeCloudCheck || durMs > 3500) return
            } else if (!cfg.wakeCloudCheck || durMs > 3500) return // asleep: only short phrases, only if the user opted in
        }
        val asleepPath = wasAsleep && !wokeLocally
        if (!asleepPath) move(LiveState.THINKING)
        val t0 = now()
        // Fast path: text already streamed while the user was talking. Anything unusual -> the one-shot transcriber.
        val streamed = if (!wasAsleep && stt != null) kotlinx.coroutines.withTimeoutOrNull(3000) { stt.await() } else null
        val tr: Transcript = streamTranscript(streamed) ?: transcriber.transcribe(pcm, MicCapture.SAMPLE_RATE)
        publishDiag { it.copy(lastTranscribeMs = now() - t0, note = if (streamTranscript(streamed) != null) "stt: stream" else "stt: upload") }
        val text = when (tr) {
            is Transcript.Text -> tr.text
            Transcript.NoSpeech -> { if (!asleepPath) move(LiveState.LISTENING); return }
            is Transcript.Failed -> { _message.value = tr.message; if (!asleepPath) move(LiveState.LISTENING); return }
        }
        verifiedSpeechAt = true
        var command = text
        if (wokeLocally) {
            command = WakeWord.parse(text)?.rest ?: text // "hey ultron, youtube kholo": drop the wake phrase when it is recognised
            if (command.isBlank()) { greet(localPhrase ?: WakePhrase.HEY); return }
        } else if (wasAsleep) {
            val hit = WakeWord.parse(text) ?: return
            val phrase = hit.phrase ?: WakePhrase.HEY
            wakeInternal()
            _wake.tryEmit(phrase)
            if (hit.rest.isEmpty()) { greet(phrase); return }
            command = hit.rest
        } else if (_state.value == LiveState.SLEEP) return
        else {
            // Already awake: an explicit "Hey/Hello/Wake up ULTRON" is answered locally (no AI call, no quota).
            val hit = WakeWord.parse(text, allowBare = false)
            if (hit != null) {
                val phrase = hit.phrase ?: WakePhrase.HEY
                _wake.tryEmit(phrase)
                if (hit.rest.isEmpty()) { _heard.value = text; greet(phrase); move(LiveState.LISTENING); clock.reset(now()); return }
                command = hit.rest
            }
        }
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
            if (talking) lastOwnSpeechMs = now()
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

    private fun greet(phrase: WakePhrase) = speakShort(phrase.greeting)

    private fun speakShort(line: String) {
        val tok = tts.beginSpeech(0L); tts.enqueue(tok, line); tts.endSpeech(tok)
    }

    // ---------- lifecycle ----------
    private fun goToSleep() {
        if (!move(LiveState.SLEEP)) return
        val localWake = cfg.wakePersonal && personal?.ready == true
        if (!cfg.wakeCloudCheck && !localWake) {
            // No wake-check opted in: nothing could ever use the audio, so the mic is released (and restarted on wake).
            mic.stop(); liveStt?.close(); streaming = false
            diagnostics.log("live", "sleep: recorder released (no wake check enabled)")
            _message.value = "Wake word abhi set nahi hai, isliye mic band hai. Menu → Awaaz/Wake mein apni awaaz se \"Ultron\" record karo, ya mic button dabao."
        }
        publishDiag()
    }

    /** Leave SLEEP (notification action, Home tap, or recognised wake phrase). */
    fun wake() { scope.launch { start() } }

    private fun wakeInternal() {
        if (!mic.isRunning) { start(); return } // claims STARTING itself (no STOPPED flicker)
        move(LiveState.LISTENING); clock.reset(now())
    }

    override fun pause() {
        if (!isActive) return
        move(LiveState.PAUSED)
        mic.stop(); liveStt?.close(); streaming = false
        endCall()
        _level.value = 0f
        publishDiag()
    }

    override fun stop() {
        if (_state.value == LiveState.STOPPED) return
        synchronized(stateLock) { _state.value = LiveState.STOPPED }
        processor?.cancel(); ticker?.cancel(); watcher?.cancel()
        processor = null; ticker = null; watcher = null
        mic.stop(); liveStt?.close(); streaming = false
        endCall(); forceNormal = false
        turns.cancel()
        utterances.tryReceive()
        restoreAudioRoute()
        _level.value = 0f
        _message.value = null
        vad.reset()
        publishDiag()
    }

    /**
     * Audio focus for phone-call mode only. ULTRON's own TTS also asks for focus, which makes the system tell us we "lost" it;
     * those events are ignored while ULTRON speaks (and for 2 s after). A real phone call / another app taking the
     * microphone-side focus pauses the session, so the mic is never held against a call.
     */
    private fun acquireFocus() {
        val a = audioSession ?: return
        if (!a.acquire { ev -> onFocusEvent(ev) }) diagnostics.log("live", "audio focus refused")
    }

    private fun onFocusEvent(ev: com.ultron.ai.voice.AudioSessionManager.FocusEvent) {
        val ownSpeech = tts.isActive() || _state.value == LiveState.SPEAKING || now() - lastOwnSpeechMs < 2000
        diagnostics.log("live", "focus $ev ownSpeech=$ownSpeech")
        if (ownSpeech) return
        if (ev == com.ultron.ai.voice.AudioSessionManager.FocusEvent.LOST_PERMANENT ||
            ev == com.ultron.ai.voice.AudioSessionManager.FocusEvent.LOST_TRANSIENT) {
            _message.value = "Doosri audio/call ne focus liya, isliye Live pause hua. Mic button se dobara chalao."
            pause()
        }
    }

    @Volatile private var lastOwnSpeechMs = 0L

    private fun endCall() { audioSession?.release(); if (inCall) { inCall = false; call?.stop() } }

    private fun onFatal(reason: String) {
        scope.launch {
            if (reason == "PERMISSION") { synchronized(stateLock) { _state.value = LiveState.PERMISSION_REQUIRED }; _message.value = "Microphone permission chahiye." }
            else { synchronized(stateLock) { _state.value = LiveState.AUDIO_ERROR }; _message.value = reason }
            processor?.cancel(); ticker?.cancel(); watcher?.cancel()
            mic.stop(); liveStt?.close(); streaming = false; endCall(); restoreAudioRoute(); _level.value = 0f
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
