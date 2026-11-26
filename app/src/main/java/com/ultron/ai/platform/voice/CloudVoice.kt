package com.ultron.ai.platform.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.GeminiWire
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.live.LipSync
import com.ultron.ai.domain.live.MouthFrames
import com.ultron.ai.domain.live.Wav
import java.io.IOException
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PcmClip(val text: String, val pcm: ShortArray?, val rate: Int)
private class LipClip(val startSample: Long, val rate: Int, val frames: MouthFrames, val endSample: Long)

/** (jaw opening 0..1, shape -1 round .. +1 wide) of the audio being heard right now. */
data class MouthPose(val open: Float = 0f, val shape: Float = 0f, val live: Boolean = false)

/**
 * Online natural voices (Gemini TTS) with the user's own key. Sentences are synthesised in parallel (max 2)
 * but played strictly in order through ONE AudioTrack, so replies start after the first sentence is ready
 * and never overlap. If a sentence fails to synthesise it is handed back to the device voice instead of
 * being silently dropped. Needs internet; quality/latency depend on Google's service.
 */
class CloudVoice(
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    scope: CoroutineScope,
) {
    private val media = "application/json; charset=utf-8".toMediaType()
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cfgEnabled = true
    @Volatile private var cfgVoice = "Algenib"
    @Volatile private var cfgModel = "gemini-2.5-flash-preview-tts"
    @Volatile private var cfgVolume = 1f
    @Volatile private var cfgStyle = GeminiWire.VOICE_STYLE
    @Volatile private var consent = false
    @Volatile private var cfgFx = true
    @Volatile private var cfgBreath = true
    private var clipCounter = 0

    /** true → AudioTrack uses the voice-communication usage so the platform echo canceller can see it. */
    @Volatile var communicationUsage = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _mouth = MutableStateFlow(MouthPose())
    /** Real audio-driven mouth pose; live=false means "no cloud audio is playing, use the approximate fallback". */
    val mouth: StateFlow<MouthPose> = _mouth

    /** Mouth opening (0..1) measured from audio played by another component (the live call). 0 = closed/off. */
    fun pushExternalMouth(open: Float) { _mouth.value = if (open <= 0.01f) MouthPose() else MouthPose(open = open, shape = 0.3f, live = true) }
    private val lipClips = java.util.concurrent.CopyOnWriteArrayList<LipClip>()
    private var lipJob: Job? = null
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val lock = Any()
    private var generation = 0
    private var channel: Channel<Deferred<PcmClip>>? = null
    private var player: Job? = null
    private var track: AudioTrack? = null
    private var sessionActive = false
    private var trackRate = 24_000
    private val limiter = Semaphore(2)

    init {
        scope.launch {
            settings.settings.collect { s ->
                cfgEnabled = s.cloudVoice
                cfgVoice = s.cloudVoiceName.takeIf { n -> GeminiWire.CLOUD_VOICES.any { it.first == n } } ?: "Algenib"
                cfgModel = s.cloudTtsModel.takeIf(GeminiWire::isSafeModelId) ?: "gemini-2.5-flash-preview-tts"
                cfgVolume = s.ttsVolume
                cfgStyle = s.voiceStyle.ifBlank { GeminiWire.VOICE_STYLE }
                consent = s.cloudConsent
                cfgFx = s.voiceFx; cfgBreath = s.voiceBreath
            }
        }
    }

    /** Device synthesis hook (set by TtsController): text -> PCM, so even the phone's own voice can be lip-synced. */
    @Volatile var deviceSynth: (suspend (String) -> PcmClip?)? = null

    @Volatile private var blockedUntil = 0L
    private val _cloudCalls = MutableStateFlow(0)
    /** Real number of Gemini TTS requests made since the app started (each one counts against your quota). */
    val cloudCalls: StateFlow<Int> = _cloudCalls
    private var batch = StringBuilder()

    /** Gemini voice is used only with consent, a saved key, the toggle on, and not during a quota cooldown. */
    fun useCloud(): Boolean = cfgEnabled && consent && settings.geminiKey() != null && System.currentTimeMillis() >= blockedUntil
    fun cooldownLeftSec(): Int = ((blockedUntil - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
    /** The player is used whenever it can produce audio itself (Gemini or device synthesis). */
    fun enabled(): Boolean = useCloud() || deviceSynth != null
    val active: Boolean get() = synchronized(lock) { sessionActive }

    fun begin(): Int {
        val gen: Int
        synchronized(lock) {
            stopLocked()
            gen = ++generation
            sessionActive = true
            sentFirst = false
            clipCounter = 0
            batch = StringBuilder()
            channel = Channel(8)
        }
        val ch = synchronized(lock) { channel }!!
        player = io.launch { playLoop(gen, ch) }
        return gen
    }

    fun enqueue(token: Int, text: String, fallback: (String) -> Unit, onFirstAudio: () -> Unit) {
        val ch: Channel<Deferred<PcmClip>>
        synchronized(lock) { if (token != generation) return; ch = channel ?: return }
        pendingFallback = fallback
        firstAudioCb = onFirstAudio
        if (useCloud()) {
            // Quota saver: the first sentence goes out at once (fast start); the rest is merged into ONE request.
            val first = synchronized(lock) { val f = !sentFirst; sentFirst = true; f }
            if (first) { submit(ch, text, fallback); return }
            val flushNow = synchronized(lock) { batch.append(if (batch.isEmpty()) "" else " ").append(text); batch.length >= 260 }
            if (flushNow) flushBatch(ch, fallback)
            return
        }
        submit(ch, text, fallback)
    }

    private var sentFirst = false

    private fun submit(ch: Channel<Deferred<PcmClip>>, text: String, fallback: (String) -> Unit) {
        val idx = synchronized(lock) { clipCounter++ }
        val d = io.async { limiter.withPermit { synth(text, idx) } }
        // trySend keeps the exact enqueue order (separate launched sends could reorder sentences).
        if (ch.trySend(d).isFailure) { d.cancel(); fallback(text) }
    }

    private fun flushBatch(ch: Channel<Deferred<PcmClip>>, fallback: (String) -> Unit) {
        val t = synchronized(lock) { val x = batch.toString(); batch = StringBuilder(); x }
        if (t.isNotBlank()) submit(ch, t, fallback)
    }

    @Volatile private var pendingFallback: ((String) -> Unit)? = null
    @Volatile private var firstAudioCb: (() -> Unit)? = null

    fun end(token: Int) {
        val ch: Channel<Deferred<PcmClip>>?
        synchronized(lock) { if (token != generation) return; ch = channel }
        if (ch != null) pendingFallback?.let { flushBatch(ch, it) }
        ch?.close()
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        generation++
        sessionActive = false
        channel?.close()
        channel = null
        player?.cancel()
        player = null
        releaseTrack()
        lipJob?.cancel(); lipJob = null; lipClips.clear()
        _mouth.value = MouthPose()
        _speaking.value = false
    }

    private fun releaseTrack() {
        track?.let { try { it.pause(); it.flush() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        track = null
    }

    private suspend fun playLoop(gen: Int, ch: Channel<Deferred<PcmClip>>) {
        var written = 0L
        var first = true
        try {
            for (d in ch) {
                val clip = d.await()
                if (synchronized(lock) { gen != generation }) return
                val raw = clip.pcm
                if (raw == null) { pendingFallback?.invoke(clip.text); continue }
                if (track == null) { trackRate = clip.rate; track = newTrack(clip.rate) }
                val t = track!!
                // One track = one sample rate: a clip from another source (e.g. phone voice after a Gemini clip) is resampled.
                val pcm = if (clip.rate == trackRate) raw else Wav.resample(raw, clip.rate, trackRate)
                lipClips += LipClip(written, trackRate, LipSync.analyze(pcm, trackRate), written + pcm.size)
                if (first) {
                    first = false; firstAudioCb?.invoke(); _speaking.value = true; t.play()
                    lipJob?.cancel()
                    lipJob = io.launch { followPlayhead(gen, t) }
                }
                var off = 0
                while (off < pcm.size) {
                    if (synchronized(lock) { gen != generation }) return
                    val n = t.write(pcm, off, minOf(pcm.size - off, 4800))
                    if (n < 0) { diagnostics.log("cvoice", "track write error $n"); return }
                    off += n
                }
                written += pcm.size
            }
            // Wait for the last buffer to actually be played before reporting "finished".
            val t = track
            if (t != null && written > 0) {
                val deadline = System.nanoTime() + 20_000_000_000L
                while (synchronized(lock) { gen == generation } && t.playbackHeadPosition < written && System.nanoTime() < deadline) {
                    kotlinx.coroutines.delay(30)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log("cvoice", "playback ${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                if (gen == generation) { sessionActive = false; releaseTrack(); lipJob?.cancel(); lipJob = null; lipClips.clear(); _mouth.value = MouthPose(); _speaking.value = false }
            }
        }
    }

    /** Reads the speaker's real playback position ~60x/s and publishes the matching mouth pose. */
    private suspend fun followPlayhead(gen: Int, t: AudioTrack) {
        var lastOpen = -1f
        while (synchronized(lock) { gen == generation }) {
            val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Exception) { break }
            val clip = lipClips.lastOrNull { head >= it.startSample && head < it.endSample }
            val pose = if (clip == null) MouthPose(0f, 0f, true) else {
                val (o, sh) = LipSync.at(clip.frames, clip.rate, head - clip.startSample)
                MouthPose(o, sh, true)
            }
            if (abs(pose.open - lastOpen) > 0.01f || pose.open == 0f) { _mouth.value = pose; lastOpen = pose.open }
            kotlinx.coroutines.delay(16)
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val usage = if (communicationUsage) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(cfgVolume.coerceIn(0f, 1f))
        return t
    }

    /** Synthesise one chunk with Gemini (if allowed) else the device voice. pcm=null means "could not": caller speaks it directly. */
    private suspend fun synth(text: String, idx: Int = 0): PcmClip {
        if (useCloud()) {
            val c = cloudSynth(text)
            if (c.pcm != null) return c
        }
        val clip = deviceSynth?.invoke(text) ?: return PcmClip(text, null, 0)
        val raw = clip.pcm
        if (raw == null || (!cfgFx && !cfgBreath)) return clip
        // Offline Ultron treatment (CPU only, a few ms per sentence): body, rasp, metallic comb, hall, breaths.
        val shaped = try {
            com.ultron.ai.domain.voice.UltronVoiceFx.process(raw, clip.rate, idx, text.hashCode(), com.ultron.ai.domain.voice.UltronVoiceFx.Options(cfgFx, cfgBreath))
        } catch (e: Exception) { diagnostics.log("cvoice", "fx failed ${e.javaClass.simpleName}"); raw }
        return PcmClip(clip.text, shaped, clip.rate)
    }

    private suspend fun cloudSynth(text: String): PcmClip = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val key = settings.geminiKey() ?: return@withContext PcmClip(text, null, 0)
        _cloudCalls.value = _cloudCalls.value + 1
        try {
            val req = Request.Builder().url(GeminiWire.generateUrl(cfgModel)).header("x-goog-api-key", key)
                .post(GeminiWire.ttsBody(text, cfgVoice, cfgStyle).toRequestBody(media)).build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    if (r.code == 429) blockedUntil = System.currentTimeMillis() + 120_000 // stop burning quota for 2 minutes
                    _lastError.value = when (r.code) {
                        401, 403 -> "Online awaaz: key/permission nahi."
                        404 -> "Online awaaz model \"$cfgModel\" nahi mila. Voice Studio mein model naam badlo."
                        429 -> "Online awaaz ki Gemini limit poori. 2 minute device voice use hogi."
                        else -> "Online awaaz error (HTTP ${r.code}). Device voice use ho rahi hai."
                    }
                    diagnostics.log("cvoice", "http ${r.code}")
                    return@use PcmClip(text, null, 0)
                }
                val a = GeminiWire.parseTtsAudio(body)
                if (a == null) { _lastError.value = "Online awaaz se audio nahi mila."; return@use PcmClip(text, null, 0) }
                _lastError.value = null
                PcmClip(text, Wav.pcmFromBytes(Base64.decode(a.base64, Base64.DEFAULT)), a.sampleRate)
            }
        } catch (e: IOException) {
            diagnostics.log("cvoice", "io ${e.javaClass.simpleName}")
            _lastError.value = "Internet nahi hai: device voice use ho rahi hai."
            PcmClip(text, null, 0)
        }
    }

    /** Preview helper for Voice Studio: plays one sample via the normal pipeline. */
    fun release() { io.cancel() }
}
