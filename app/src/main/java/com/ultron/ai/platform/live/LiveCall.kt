package com.ultron.ai.platform.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.live.LiveWire
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A phone-call style conversation over the Gemini Live API: the microphone is streamed continuously, ULTRON's voice
 * streams back and is played the moment it arrives, and the server stops it when the user speaks over it
 * (barge-in). The microphone is owned by [LiveSessionEngine]/[MicCapture]; this class only receives its frames.
 * While a call is active ALL microphone audio goes to Google (the live notification and edge light show it).
 * The API key goes in the documented `key` query parameter and is never logged.
 */
class LiveCall(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    /** Runs a validated local action requested by the model; returns a short result sentence for the model. */
    private val runTool: suspend (name: String, args: Map<String, String>) -> String,
    /** Real jaw opening 0..1 computed from the audio being played, for the avatar. */
    private val onMouth: (Float) -> Unit,
    /** Measured: first words of the user's turn heard by the server -> first audio chunk of the reply (ms). */
    private val onFirstAudioMs: (Long) -> Unit = {},
) {
    @Volatile private var heardAt = 0L
    interface Listener {
        fun onReady()
        fun onSpeaking(speaking: Boolean)
        fun onHeard(text: String)
        fun onSaid(text: String)
        fun onFailed(reason: String, retryable: Boolean)
        fun onEnded()
    }

    private val client = baseClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val lock = Any()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var listener: Listener? = null
    @Volatile private var generation = 0
    private val out = ArrayList<Short>(3200)

    private var audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var track: AudioTrack? = null
    private var written = 0L
    @Volatile private var speaking = false
    private var player: Job? = null
    private var monitor: Job? = null
    @Volatile private var volume = 1f
    @Volatile private var communication = false

    val isActive: Boolean get() = ws != null
    val isReady: Boolean get() = ready && ws != null

    /** Raw Live events for the shared chat (transcripts, completion, honest disconnect). One sink for the one session. */
    interface Sink {
        fun onEvent(heard: String?, said: String?, turnComplete: Boolean, interrupted: Boolean)
        fun onDown(reason: String)
        suspend fun history(): List<Pair<String, String>>
    }
    @Volatile var sink: Sink? = null
    /** Typed-message replies the user did not want spoken: audio is dropped (text still arrives as transcript). */
    @Volatile var muteAudio = false
    private val handledTools = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Last connection failure (cleared when a session becomes ready): feeds the Capability Registry ERROR state. */
    @Volatile var lastFailure: String? = null

    /** Hand the already-running session to another owner (voice engine) WITHOUT reconnecting: same socket, same context. */
    fun attach(l: Listener) { listener = l; muteAudio = false; if (ready) l.onReady() }

    /** Typed input into the same session. False = not sent (no ready session); nothing is queued silently. */
    fun sendText(t: String): Boolean {
        val w = ws ?: return false
        if (!ready) return false
        return w.send(LiveWire.text(t))
    }

    fun start(l: Listener, systemPrompt: String, voice: String, volume: Float, communication: Boolean) {
        stop()
        listener = l; this.volume = volume; this.communication = communication
        val gen = ++generation
        scope.launch {
            val s = settings.settings.first()
            val key = settings.geminiKey()
            if (!s.cloudConsent || key == null) { l.onFailed("Live call ke liye Cloud AI allow aur Gemini key chahiye.", false); return@launch }
            try {
                val req = Request.Builder().url("${LiveWire.HOST_PATH}?key=$key").build()
                val sock = client.newWebSocket(req, Handler(gen, systemPrompt, voice))
                synchronized(lock) { if (gen == generation) ws = sock else sock.cancel() }
            } catch (e: Exception) {
                diagnostics.log("call", "connect failed ${e.javaClass.simpleName}")
                l.onFailed("Live call connect nahi hua.", true)
            }
        }
        startPlayer(gen)
    }

    fun stop() {
        generation++
        val w: WebSocket?
        synchronized(lock) { w = ws; ws = null; ready = false; out.clear() }
        try { w?.close(1000, "bye") } catch (_: Exception) {}
        player?.cancel(); monitor?.cancel(); player = null; monitor = null
        audioQueue.close(); audioQueue = Channel(Channel.UNLIMITED)
        releaseTrack()
        if (speaking) { speaking = false; onMouth(0f) }
        listener = null
    }

    /** Called from the audio thread with 20 ms of 16 kHz mono PCM. */
    fun sendMic(frame: ShortArray) {
        if (!ready) return
        val w = ws ?: return
        var chunk: ShortArray? = null
        synchronized(lock) {
            for (s in frame) out.add(s)
            if (out.size >= 1600) { chunk = ShortArray(out.size) { out[it] }; out.clear() }
        }
        val c = chunk ?: return
        val bb = ByteBuffer.allocate(c.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in c) bb.putShort(s)
        w.send(LiveWire.audio(Base64.encodeToString(bb.array(), Base64.NO_WRAP)))
    }

    private inner class Handler(val gen: Int, val prompt: String, val voice: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(LiveWire.setupCall(prompt, voice)) }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down(gen, "closed $code", code != 1000)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            down(gen, "failure ${t.javaClass.simpleName} ${response?.code ?: ""}", true, response?.code)
    }

    private fun handle(gen: Int, text: String) {
        if (gen != generation) return
        val m = LiveWire.parseCall(text) ?: return
        val l = listener ?: return
        when {
            m.setupComplete -> {
                ready = true; lastFailure = null; diagnostics.log("call", "ready"); l.onReady()
                sink?.let { sk -> scope.launch { // restore earlier conversation context into a fresh session
                    val h = try { sk.history() } catch (_: Exception) { emptyList() }
                    if (h.isNotEmpty() && gen == generation) ws?.send(LiveWire.history(h))
                } }
            }
            m.error != null -> down(gen, "server error", false)
            m.goAway -> down(gen, "goAway", true)
        }
        m.heard?.takeIf { it.isNotBlank() }?.let { if (heardAt == 0L) heardAt = android.os.SystemClock.elapsedRealtime() }
        if (m.audio.isNotEmpty() && heardAt != 0L) { onFirstAudioMs(android.os.SystemClock.elapsedRealtime() - heardAt); heardAt = 0L }
        if (m.turnComplete || m.interrupted) heardAt = 0L
        if (m.heard != null || m.said != null || m.turnComplete || m.interrupted) sink?.onEvent(m.heard, m.said, m.turnComplete, m.interrupted)
        if (!muteAudio) m.audio.forEach { b64 ->
            val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return@forEach }
            audioQueue.trySend(bytes)
        }
        if (m.interrupted) interrupt()
        m.heard?.takeIf { it.isNotBlank() }?.let(l::onHeard)
        m.said?.takeIf { it.isNotBlank() }?.let(l::onSaid)
        for (t in m.tools) scope.launch {
            // Duplicate tool calls (same id) are executed once; a call must never run twice (two calls / two messages).
            if (!handledTools.add(t.id)) { diagnostics.log("call", "duplicate tool call ignored: ${t.name}"); return@launch }
            diagnostics.log("call", "tool call: ${t.name}")
            val r = try { runTool(t.name, t.args) } catch (e: Exception) { "FAILED: ${e.javaClass.simpleName} ${e.message?.take(120).orEmpty()}" }
            diagnostics.log("call", "tool result: ${t.name} len=${r.length}")
            if (gen == generation) ws?.send(LiveWire.toolResponse(t.id, t.name, r))
            if (t.name == "stop_listening" && gen == generation) { delay(1200); l.onEnded() }
        }
    }

    private fun down(gen: Int, why: String, retryable: Boolean, httpCode: Int? = null) {
        if (gen != generation) return
        diagnostics.log("call", "down: $why")
        val l = listener
        val msg = when (httpCode) {
            401, 403 -> "Live call: key ko Live API ki permission nahi."
            404 -> "Live call model nahi mila."
            429 -> "Live call ki limit poori (Gemini free key par Live ki limit chhoti hoti hai)."
            else -> "Live call toot gayi ($why)."
        }
        lastFailure = msg
        sink?.onDown(msg)
        stop()
        l?.onFailed(msg, retryable && httpCode != 401 && httpCode != 403 && httpCode != 404 && httpCode != 429)
    }

    // ----------------------------- playback -----------------------------

    private fun interrupt() {
        // Drop everything that was queued and silence the speaker immediately.
        while (audioQueue.tryReceive().isSuccess) { /* drain */ }
        val t = track
        if (t != null) { try { t.pause(); t.flush(); t.play() } catch (_: Exception) {} }
        written = 0
        if (speaking) { speaking = false; onMouth(0f); listener?.onSpeaking(false) }
    }

    private fun startPlayer(gen: Int) {
        val q = audioQueue
        player = scope.launch(Dispatchers.IO) {
            try {
                for (bytes in q) {
                    if (gen != generation) break
                    val t = track ?: newTrack().also { track = it; it.play() }
                    val sh = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                    if (!speaking) { speaking = true; listener?.onSpeaking(true) }
                    var off = 0
                    while (off < sh.size && gen == generation) {
                        val n = t.write(sh, off, minOf(sh.size - off, 960))
                        if (n < 0) break
                        var e = 0.0; for (i in off until off + n) { val v = sh[i] / 32768.0; e += v * v }
                        onMouth((sqrt(e / n.coerceAtLeast(1)) * 6.0).toFloat().coerceIn(0f, 1f))
                        off += n; written += n
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) { diagnostics.log("call", "play ${e.javaClass.simpleName}") }
        }
        monitor = scope.launch(Dispatchers.IO) {
            while (gen == generation) {
                delay(120)
                val t = track ?: continue
                if (speaking && q.isEmpty) {
                    val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Exception) { written }
                    if (head >= written - 240) { speaking = false; onMouth(0f); listener?.onSpeaking(false) }
                }
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val rate = LiveWire.OUT_RATE
        val usage = if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume.coerceIn(0f, 1f))
        written = 0
        return t
    }

    private fun releaseTrack() {
        val t = track; track = null
        if (t != null) { try { t.pause(); t.flush() } catch (_: Exception) {}; try { t.release() } catch (_: Exception) {} }
        written = 0
    }
}
