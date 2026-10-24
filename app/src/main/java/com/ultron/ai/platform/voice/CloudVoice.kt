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

private class Clip(val text: String, val pcm: ShortArray?, val rate: Int)
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
    @Volatile private var consent = false

    /** true → AudioTrack uses the voice-communication usage so the platform echo canceller can see it. */
    @Volatile var communicationUsage = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _mouth = MutableStateFlow(MouthPose())
    /** Real audio-driven mouth pose; live=false means "no cloud audio is playing, use the approximate fallback". */
    val mouth: StateFlow<MouthPose> = _mouth
    private val lipClips = java.util.concurrent.CopyOnWriteArrayList<LipClip>()
    private var lipJob: Job? = null
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val lock = Any()
    private var generation = 0
    private var channel: Channel<Deferred<Clip>>? = null
    private var player: Job? = null
    private var track: AudioTrack? = null
    private var sessionActive = false
    private val limiter = Semaphore(2)

    init {
        scope.launch {
            settings.settings.collect { s ->
                cfgEnabled = s.cloudVoice
                cfgVoice = s.cloudVoiceName.takeIf { n -> GeminiWire.CLOUD_VOICES.any { it.first == n } } ?: "Algenib"
                cfgModel = s.cloudTtsModel.takeIf(GeminiWire::isSafeModelId) ?: "gemini-2.5-flash-preview-tts"
                cfgVolume = s.ttsVolume
                consent = s.cloudConsent
            }
        }
    }

    /** Cloud voice is used only with consent, a saved key, and the toggle on. */
    fun enabled(): Boolean = cfgEnabled && consent && settings.geminiKey() != null
    val active: Boolean get() = synchronized(lock) { sessionActive }

    fun begin(): Int {
        val gen: Int
        synchronized(lock) {
            stopLocked()
            gen = ++generation
            sessionActive = true
            channel = Channel(8)
        }
        val ch = synchronized(lock) { channel }!!
        player = io.launch { playLoop(gen, ch) }
        return gen
    }

    fun enqueue(token: Int, text: String, fallback: (String) -> Unit, onFirstAudio: () -> Unit) {
        val ch: Channel<Deferred<Clip>>
        synchronized(lock) { if (token != generation) return; ch = channel ?: return }
        pendingFallback = fallback
        firstAudioCb = onFirstAudio
        val d = io.async { limiter.withPermit { synth(text) } }
        // trySend keeps the exact enqueue order (separate launched sends could reorder sentences).
        if (ch.trySend(d).isFailure) { d.cancel(); fallback(text) }
    }

    @Volatile private var pendingFallback: ((String) -> Unit)? = null
    @Volatile private var firstAudioCb: (() -> Unit)? = null

    fun end(token: Int) {
        val ch: Channel<Deferred<Clip>>?
        synchronized(lock) { if (token != generation) return; ch = channel }
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

    private suspend fun playLoop(gen: Int, ch: Channel<Deferred<Clip>>) {
        var written = 0L
        var first = true
        try {
            for (d in ch) {
                val clip = d.await()
                if (synchronized(lock) { gen != generation }) return
                val pcm = clip.pcm
                if (pcm == null) { pendingFallback?.invoke(clip.text); continue }
                val t = track ?: newTrack(clip.rate).also { track = it }
                lipClips += LipClip(written, clip.rate, LipSync.analyze(pcm, clip.rate), written + pcm.size)
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

    /** Synthesise one chunk. Returns a clip with pcm=null on failure (caller falls back to the device voice). */
    private suspend fun synth(text: String, voice: String = cfgVoice): Clip = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val key = settings.geminiKey() ?: return@withContext Clip(text, null, 0)
        try {
            val req = Request.Builder().url(GeminiWire.generateUrl(cfgModel)).header("x-goog-api-key", key)
                .post(GeminiWire.ttsBody(text, voice).toRequestBody(media)).build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    _lastError.value = when (r.code) {
                        401, 403 -> "Online awaaz: key/permission nahi."
                        404 -> "Online awaaz model \"$cfgModel\" nahi mila. Voice Studio mein model naam badlo."
                        429 -> "Online awaaz ki limit poori. Device voice use ho rahi hai."
                        else -> "Online awaaz error (HTTP ${r.code}). Device voice use ho rahi hai."
                    }
                    diagnostics.log("cvoice", "http ${r.code}")
                    return@use Clip(text, null, 0)
                }
                val a = GeminiWire.parseTtsAudio(body)
                if (a == null) { _lastError.value = "Online awaaz se audio nahi mila."; return@use Clip(text, null, 0) }
                _lastError.value = null
                Clip(text, Wav.pcmFromBytes(Base64.decode(a.base64, Base64.DEFAULT)), a.sampleRate)
            }
        } catch (e: IOException) {
            diagnostics.log("cvoice", "io ${e.javaClass.simpleName}")
            _lastError.value = "Internet nahi hai: device voice use ho rahi hai."
            Clip(text, null, 0)
        }
    }

    /** Preview helper for Voice Studio: plays one sample via the normal pipeline. */
    fun release() { io.cancel() }
}
