package com.ultron.ai.platform.live

import android.util.Base64
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.live.LiveWire
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
 * Streaming speech-to-text over the Gemini Live API WebSocket. Audio is sent ONLY while the voice detector says the
 * user is speaking (activityStart ... activityEnd); silence and ULTRON's own voice are never sent. If anything goes
 * wrong (consent off, no key, socket closed, no answer in time) every call degrades to "no result" and the engine
 * falls back to the one-shot [GeminiTranscriber]. The API key is passed as the documented `key` query parameter; the
 * URL is never logged.
 *
 * Result of an utterance: text (may be blank = no speech) or null = streaming gave nothing, use the fallback.
 */
class LiveTranscriber(
    private val settings: SettingsRepository,
    private val baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val client = baseClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var connecting = false

    private class Utt {
        val text = StringBuilder()
        @Volatile var gotAny = false
        @Volatile var lastMsgAt = 0L
        @Volatile var ended = false
        @Volatile var turnComplete = false
        val result = CompletableDeferred<String?>()
        val pending = ArrayList<Short>()
    }
    private var cur: Utt? = null

    /** Opens the socket in the background (needs cloud consent + key). Safe to call repeatedly. */
    fun connect() {
        synchronized(lock) { if (ws != null || connecting) return; connecting = true }
        scope.launch {
            val s = settings.settings.first()
            val key = settings.geminiKey()
            if (!s.cloudConsent || !s.streamStt || key == null) { synchronized(lock) { connecting = false }; return@launch }
            try {
                val req = Request.Builder().url("${LiveWire.HOST_PATH}?key=$key").build()
                val socket = client.newWebSocket(req, Listener())
                synchronized(lock) { ws = socket }
            } catch (e: Exception) {
                diagnostics.log("live-stt", "connect failed ${e.javaClass.simpleName}")
                synchronized(lock) { connecting = false }
            }
        }
    }

    fun close() {
        val w: WebSocket?
        synchronized(lock) { w = ws; ws = null; ready = false; connecting = false; cur?.result?.complete(null); cur = null }
        try { w?.close(1000, "bye") } catch (_: Exception) {}
    }

    val isReady: Boolean get() = ready

    /** Start streaming one utterance. Returns false (and streams nothing) if the socket is not ready. */
    fun begin(preRoll: ShortArray): Boolean {
        if (!ready) { connect(); return false }
        val w = ws ?: return false
        val u = Utt()
        synchronized(lock) { cur?.result?.complete(null); cur = u }
        if (!w.send(LiveWire.activityStart())) { synchronized(lock) { cur = null }; return false }
        push(preRoll)
        return true
    }

    fun push(frame: ShortArray) {
        val u = synchronized(lock) { cur } ?: return
        val w = ws ?: return
        if (u.ended) return
        for (s in frame) u.pending.add(s)
        if (u.pending.size >= 1600) flush(w, u) // ~100 ms per message
    }

    private fun flush(w: WebSocket, u: Utt) {
        if (u.pending.isEmpty()) return
        val bb = ByteBuffer.allocate(u.pending.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in u.pending) bb.putShort(s)
        u.pending.clear()
        w.send(LiveWire.audio(Base64.encodeToString(bb.array(), Base64.NO_WRAP)))
    }

    /** The user stopped talking: close the activity and wait briefly for the final text. */
    fun end(): Deferred<String?>? {
        val u: Utt
        synchronized(lock) { u = cur ?: return null }
        val w = ws
        if (w == null) { u.result.complete(null); return u.result }
        flush(w, u)
        u.ended = true
        w.send(LiveWire.activityEnd())
        u.lastMsgAt = System.currentTimeMillis()
        scope.launch {
            val start = System.currentTimeMillis()
            while (!u.result.isCompleted) {
                delay(40)
                val now = System.currentTimeMillis()
                val quiet = now - u.lastMsgAt
                if (u.turnComplete && u.gotAny) { u.result.complete(u.text.toString().trim()); break }
                if (u.gotAny && quiet >= 450) { u.result.complete(u.text.toString().trim()); break }
                if (now - start >= 2500) { u.result.complete(if (u.gotAny) u.text.toString().trim() else null); break }
            }
            synchronized(lock) { if (cur === u) cur = null }
        }
        return u.result
    }

    /** Throw the current utterance away (echo, too short, answered a confirmation...). */
    fun cancel() {
        val u: Utt?
        synchronized(lock) { u = cur; cur = null }
        if (u != null && !u.ended) ws?.send(LiveWire.activityEnd())
        u?.result?.complete(null)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(LiveWire.setup()) }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down("closed $code")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = down("failure ${t.javaClass.simpleName} ${response?.code ?: ""}")
    }

    private fun handle(text: String) {
        val m = LiveWire.parse(text) ?: return
        if (m.setupComplete) { ready = true; synchronized(lock) { connecting = false }; diagnostics.log("live-stt", "ready"); return }
        if (m.error != null) { diagnostics.log("live-stt", "server error"); down("error"); return }
        val u = synchronized(lock) { cur } ?: return
        val now = System.currentTimeMillis()
        m.finalText?.let { if (it.isNotEmpty()) { u.text.append(it); u.gotAny = true; u.lastMsgAt = now } }
        if (m.finalText == null && m.interimText != null) u.lastMsgAt = now
        if (m.turnComplete) { u.turnComplete = true; u.gotAny = true; u.lastMsgAt = now }
    }

    private fun down(why: String) {
        diagnostics.log("live-stt", "down: $why")
        synchronized(lock) { ws = null; ready = false; connecting = false; cur?.result?.complete(null); cur = null }
    }
}
