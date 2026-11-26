package com.ultron.ai.platform.live

import android.content.Context
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.domain.live.VadConfig
import com.ultron.ai.domain.live.VadEvent
import com.ultron.ai.domain.live.VadSegmenter
import com.ultron.ai.domain.wake.Mfcc
import com.ultron.ai.domain.wake.PhraseWakeMatcher
import com.ultron.ai.domain.wake.WakeDecision
import com.ultron.ai.domain.wake.WakeMatcher
import com.ultron.ai.domain.wake.WakePhrase
import com.ultron.ai.domain.wake.WakePhraseStore
import com.ultron.ai.domain.wake.WakeTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Personal, offline wake detector for "Hey ULTRON", "Hello ULTRON" and "Wake up ULTRON": the user records each phrase a
 * few times; later utterances are compared with those recordings (MFCC + DTW). It never leaves the phone and costs no API quota.
 * Old single-phrase recordings (wake_templates.bin) are read as "Hey ULTRON" so nothing the user already recorded is lost.
 * Honest limits: it recognises YOUR way of saying the phrase (speaker-dependent), not arbitrary voices, and it is
 * a template matcher, not a neural wake-word engine. Only voice features are stored, never audio.
 */
class PersonalWake(context: Context, private val mic: MicCapture, private val diagnostics: DiagnosticsLog) : com.ultron.ai.voice.WakeWordEngine {
    private val legacyFile = File(context.filesDir, "wake_templates.bin")
    private val file = File(context.filesDir, "wake_phrases.bin")
    private val _sets = MutableStateFlow<Map<WakePhrase, List<WakeTemplate>>>(emptyMap())
    /** Templates per phrase. */
    val phrases: StateFlow<Map<WakePhrase, List<WakeTemplate>>> = _sets
    private val _templates = MutableStateFlow<List<WakeTemplate>>(emptyList())
    /** All templates of all phrases (flat; kept for older callers). */
    val templates: StateFlow<List<WakeTemplate>> = _templates

    val needed = 4
    /** True when at least one phrase has enough recordings to be matched. */
    override val ready: Boolean get() = PhraseWakeMatcher.ready(_sets.value)
    fun ready(phrase: WakePhrase): Boolean = count(phrase) >= PhraseWakeMatcher.MIN_TEMPLATES
    fun count(phrase: WakePhrase): Int = _sets.value[phrase]?.size ?: 0
    /** Longest utterance (ms) worth checking; longer speech is skipped before any feature extraction. */
    fun maxWakeUtteranceMs(): Long = PhraseWakeMatcher.maxUtteranceMs(_sets.value)

    override fun detect(pcm16k: ShortArray, sensitivity: Float): com.ultron.ai.voice.WakeWordEngine.Detection? =
        decide(pcm16k, sensitivity)?.let {
            // The engine contract is "score <= threshold means match": a rejected-but-close decision must not look like one.
            com.ultron.ai.voice.WakeWordEngine.Detection(if (it.match) it.score else maxOf(it.score, it.threshold * 1.01f), it.threshold)
        }

    /** Which phrase (if any) was said. Null when there are no usable recordings or the audio has no usable features. */
    fun decide(pcm: ShortArray, sensitivity: Float): WakeDecision? {
        val sets = _sets.value
        if (!PhraseWakeMatcher.ready(sets)) return null
        val f = Mfcc.computeRaw(pcm)
        if (f.size < 20) return null
        return PhraseWakeMatcher.decide(sets, f, sensitivity)
    }

    sealed interface Rec {
        data class Ok(val pcm: ShortArray) : Rec
        data class Failed(val message: String) : Rec
    }
    data class Result(val score: Float, val threshold: Float) { val match get() = score <= threshold }

    init { load() }

    fun load() {
        val loaded: Map<WakePhrase, List<WakeTemplate>> = try {
            when {
                file.exists() -> WakePhraseStore.deserialize(file.readBytes())
                // Recordings made before the three-phrase upgrade were "Hey Ultron".
                legacyFile.exists() -> WakeMatcher.deserialize(legacyFile.readBytes()).takeIf { it.isNotEmpty() }?.let { mapOf(WakePhrase.HEY to it) } ?: emptyMap()
                else -> emptyMap()
            }
        } catch (e: Exception) { diagnostics.log("wake", "template load failed ${e.javaClass.simpleName}"); emptyMap() }
        publish(loaded)
    }

    private fun publish(m: Map<WakePhrase, List<WakeTemplate>>) {
        _sets.value = m
        _templates.value = m.values.flatten()
    }

    private fun save() {
        try { file.writeBytes(WakePhraseStore.serialize(_sets.value)) } catch (e: Exception) { diagnostics.log("wake", "template save failed ${e.javaClass.simpleName}") }
    }

    /** Deletes ALL wake recordings (both the current and the old file). */
    fun clear() {
        publish(emptyMap())
        runCatching { file.delete() }; runCatching { legacyFile.delete() }
    }

    /** Deletes the recordings of one phrase only. */
    fun clear(phrase: WakePhrase) {
        publish(_sets.value - phrase)
        if (_sets.value.isEmpty()) { runCatching { file.delete() }; runCatching { legacyFile.delete() } } else save()
    }

    /** Records ONE phrase with the microphone (live must be stopped: only one recorder may exist). */
    suspend fun record(): Rec {
        if (mic.isRunning) return Rec.Failed("Pehle Live band karo, phir record karo (mic ek hi baar khul sakta hai).")
        if (!mic.hasPermission()) return Rec.Failed("Microphone permission chahiye.")
        val done = CompletableDeferred<Rec>()
        val vad = VadSegmenter(VadConfig(endPauseMs = 600, minSpeechMs = 250, maxUtteranceMs = 5000))
        val ok = mic.start(
            onFrame = { f -> for (e in vad.feed(f)) if (e is VadEvent.Utterance && !done.isCompleted) done.complete(Rec.Ok(e.pcm)) },
            onFatal = { r -> done.complete(Rec.Failed(if (r == "PERMISSION") "Microphone permission chahiye." else r)) },
            onSilenced = {},
        )
        if (!ok) { mic.stop(); return done.takeIf { it.isCompleted }?.await() ?: Rec.Failed("Mic khul nahi paya.") }
        val r = withTimeoutOrNull(9000) { done.await() } ?: Rec.Failed("Awaaz sunayi nahi di. Shant jagah mein, mic ke paas bolo.")
        mic.stop()
        return r
    }

    /** Adds a recorded phrase if it is a sensible length. [phrase] defaults to "Hey ULTRON" (old callers). */
    fun add(pcm: ShortArray, phrase: WakePhrase = WakePhrase.HEY): String? {
        val ms = pcm.size * 1000L / MicCapture.SAMPLE_RATE
        if (ms < 500) return "Bahut chhota tha. '${phrase.spoken}' poora bolo."
        if (ms > 3500) return "Bahut lamba tha. Sirf '${phrase.spoken}' bolo."
        val f = Mfcc.compute(pcm)
        if (f.size < 30) return "Awaaz kaafi nahi thi. Dobara bolo."
        val list = _sets.value[phrase].orEmpty()
        if (list.isNotEmpty()) {
            val d = WakeMatcher.fullDistance(list[0].features, f)
            val ref = if (list.size > 1) WakeMatcher.intraDistance(list) else d
            if (d > 3f * maxOf(ref, 1f)) return "Ye pehle recording se bahut alag laga. Wahi phrase dobara bolo."
        }
        publish(_sets.value + (phrase to (list + WakeTemplate(f)).takeLast(6)))
        save()
        return null
    }

    /** Old single-phrase test (now: best match over all enrolled phrases). */
    fun test(pcm: ShortArray, sensitivity: Float): Result? = decide(pcm, sensitivity)?.takeIf { it.score < Float.MAX_VALUE }?.let { Result(it.score, it.threshold) }
}
