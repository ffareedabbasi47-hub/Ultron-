package com.ultron.ai.platform.live

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.domain.wake.ChunkBuffer
import com.ultron.ai.domain.wake.WakeScoreGate
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Offline neural wake word (openWakeWord pipeline) running on-device with ONNX Runtime. No internet, no Gemini key.
 * Pipeline per 80 ms chunk: raw int16 audio -> melspectrogram.onnx -> (last 76 mel frames) embedding_model.onnx
 * -> (last 16 embeddings) hey_ultron.onnx -> score 0..1.
 *
 * It does NOT own the microphone. [LiveSessionEngine] has the single AudioRecord ([MicCapture]); frames are pushed
 * into [feed] only while armed (SLEEP). Inference runs on its own thread; frames are dropped (and counted) if it falls behind.
 *
 * The three model files must exist in assets/wake/. "hey_ultron.onnx" is a CUSTOM model that has to be trained for the phrase;
 * without it [ready] stays false and the app keeps using the older template matcher. See assets/wake/README.md.
 */
class OnnxWake(private val context: Context, private val diagnostics: DiagnosticsLog) {
    private val _status = MutableStateFlow("Neural wake: model files nahi mile")
    val status: StateFlow<String> = _status

    @Volatile var ready: Boolean = false; private set
    private var env: OrtEnvironment? = null
    private var mel: OrtSession? = null
    private var emb: OrtSession? = null
    private var clf: OrtSession? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ultron-onnx-wake").apply { isDaemon = true } }
    private val pending = AtomicInteger(0)
    @Volatile private var dropped = 0
    private val chunks = ChunkBuffer(CHUNK)
    private val gate = WakeScoreGate()
    private var tail = FloatArray(CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    @Volatile private var onWake: ((Float) -> Unit)? = null
    @Volatile var lastScore: Float = 0f; private set

    fun hasModelFiles(): Boolean = listOf(MEL, EMB, CLF).all { exists(it) }

    private fun exists(name: String) = try { context.assets.open("wake/$name").close(); true } catch (_: Exception) { false }

    /** Loads the three models. Safe to call repeatedly; returns [ready]. Blocking: call off the main thread. */
    @Synchronized
    fun prepare(): Boolean {
        if (ready) return true
        if (!hasModelFiles()) {
            _status.value = "Neural wake: assets/wake me melspectrogram.onnx, embedding_model.onnx, hey_ultron.onnx chahiye (README dekho). Abhi purana wake chal raha hai."
            return false
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            mel = e.createSession(context.assets.open("wake/$MEL").readBytes(), opts)
            emb = e.createSession(context.assets.open("wake/$EMB").readBytes(), opts)
            clf = e.createSession(context.assets.open("wake/$CLF").readBytes(), opts)
            env = e
            ready = true
            _status.value = "Neural wake: ready (offline, ONNX)"
            diagnostics.log("wake", "onnx models loaded")
            true
        } catch (t: Throwable) {
            release()
            _status.value = "Neural wake load fail: ${t.javaClass.simpleName}"
            diagnostics.log("wake", "onnx load failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /** Start evaluating. [sensitivity] is the user's 0.5..2 setting; [callback] runs on the inference thread. */
    fun arm(sensitivity: Float, callback: (Float) -> Unit) {
        gate.threshold = WakeScoreGate.thresholdFor(sensitivity)
        gate.reset(); chunks.clear(); melFrames.clear(); embeddings.clear(); tail = FloatArray(CONTEXT)
        onWake = callback
    }

    fun disarm() { onWake = null; chunks.clear() }

    /** Called from the audio thread with 16 kHz mono PCM. Cheap: it only queues work. */
    fun feed(frame: ShortArray) {
        if (!ready || onWake == null) return
        chunks.push(frame) { chunk ->
            if (pending.get() >= MAX_PENDING) { dropped++; return@push }
            pending.incrementAndGet()
            exec.execute {
                try { process(chunk) } catch (t: Throwable) {
                    diagnostics.log("wake", "onnx inference error: ${t.javaClass.simpleName}")
                    _status.value = "Neural wake error: ${t.javaClass.simpleName}"
                } finally { pending.decrementAndGet() }
            }
        }
    }

    private fun process(chunk: ShortArray) {
        val e = env ?: return
        val m = mel ?: return; val b = emb ?: return; val c = clf ?: return
        // openWakeWord feeds int16-range floats and gives the mel model 480 samples of left context.
        val x = FloatArray(tail.size + chunk.size)
        for (i in tail.indices) x[i] = tail[i]
        for (i in chunk.indices) x[tail.size + i] = chunk[i].toFloat()
        tail = FloatArray(CONTEXT) { i -> x[x.size - CONTEXT + i] }
        val melOut = run(e, m, x, longArrayOf(1, x.size.toLong()))
        val frames = melOut.size / MEL_BINS
        for (i in 0 until frames) melFrames.addLast(FloatArray(MEL_BINS) { melOut[i * MEL_BINS + it] / 10f + 2f })
        while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()
        if (melFrames.size < MEL_WINDOW) return
        val win = FloatArray(MEL_WINDOW * MEL_BINS)
        melFrames.forEachIndexed { r, row -> System.arraycopy(row, 0, win, r * MEL_BINS, MEL_BINS) }
        val vec = run(e, b, win, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        if (vec.size != EMB_DIM) { _status.value = "Neural wake: embedding size ${vec.size} != $EMB_DIM"; return }
        embeddings.addLast(vec)
        while (embeddings.size > EMB_WINDOW) embeddings.removeFirst()
        if (embeddings.size < EMB_WINDOW) return
        val feat = FloatArray(EMB_WINDOW * EMB_DIM)
        embeddings.forEachIndexed { r, row -> System.arraycopy(row, 0, feat, r * EMB_DIM, EMB_DIM) }
        val score = run(e, c, feat, longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIM.toLong())).firstOrNull() ?: return
        lastScore = score
        if (gate.onScore(score, System.currentTimeMillis())) {
            diagnostics.log("wake", "onnx wake detected score=%.2f threshold=%.2f".format(score, gate.threshold))
            onWake?.invoke(score)
        }
    }

    private fun run(e: OrtEnvironment, s: OrtSession, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val out = ArrayList<Float>()
                flatten(res[0].value, out)
                return out.toFloatArray()
            }
        }
    }

    private fun flatten(v: Any?, out: MutableList<Float>) {
        when (v) {
            is FloatArray -> v.forEach { out.add(it) }
            is Array<*> -> v.forEach { flatten(it, out) }
            is Float -> out.add(v)
        }
    }

    @Synchronized
    fun release() {
        ready = false; onWake = null
        runCatching { mel?.close() }; runCatching { emb?.close() }; runCatching { clf?.close() }
        mel = null; emb = null; clf = null
    }

    fun droppedChunks() = dropped

    companion object {
        const val MEL = "melspectrogram.onnx"
        const val EMB = "embedding_model.onnx"
        const val CLF = "hey_ultron.onnx"
        const val CHUNK = 1280
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val EMB_DIM = 96
        private const val EMB_WINDOW = 16
        private const val MAX_PENDING = 6
        private const val CONTEXT = 480
    }
}
