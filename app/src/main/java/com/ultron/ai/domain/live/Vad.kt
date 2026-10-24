package com.ultron.ai.domain.live

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class VadConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    /** Speech starts when level exceeds noise floor by this many dB (for [startFrames] frames). */
    val startMarginDb: Float = 11f,
    /** Speech continues until level falls below noise floor + this many dB (hysteresis: lower than start). */
    val endMarginDb: Float = 6f,
    val startFrames: Int = 3,
    /** Pause that ends an utterance. Independent from the inactivity (sleep) timeout. */
    val endPauseMs: Int = 700,
    val preRollMs: Int = 300,
    val maxUtteranceMs: Int = 20_000,
    /** Very short blips are dropped, but 150 ms still allows "haan"/"ruk". */
    val minSpeechMs: Int = 150,
    /** Absolute floor so a dead-quiet room does not make a keyboard click look like speech. */
    val minAbsoluteDb: Float = -52f,
    /** While ULTRON itself is speaking, require this much extra level before treating sound as the user (barge-in). */
    val bargeInExtraDb: Float = 10f,
)

sealed interface VadEvent {
    data object SpeechStart : VadEvent
    data class Utterance(val pcm: ShortArray, val durationMs: Int) : VadEvent
    /** Sound that looked like speech but was too short / too quiet; reported so tests and diagnostics can see it. */
    data object Rejected : VadEvent
}

/**
 * Frame-by-frame voice activity detector + utterance segmenter. It never touches the recorder:
 * speech boundaries are decided purely in memory, so the hardware capture can stay open.
 * Buffers are bounded (pre-roll ring + [VadConfig.maxUtteranceMs] cap).
 */
class VadSegmenter(private val cfg: VadConfig = VadConfig()) {
    private val frameSamples = cfg.sampleRate * cfg.frameMs / 1000
    private val preRollFrames = max(1, cfg.preRollMs / cfg.frameMs)
    private val endFrames = max(1, cfg.endPauseMs / cfg.frameMs)
    private val maxFrames = cfg.maxUtteranceMs / cfg.frameMs
    private val minFrames = max(1, cfg.minSpeechMs / cfg.frameMs)

    private val ring = ArrayDeque<ShortArray>()
    private val utter = ArrayList<ShortArray>()
    private var inSpeech = false
    private var aboveRun = 0
    private var silenceRun = 0
    private var speechFrames = 0

    /** Adaptive noise floor in dBFS. Only learns from frames that are not speech. */
    var noiseFloorDb = -60f
        private set
    var lastLevelDb = -90f
        private set
    val isSpeech: Boolean get() = inSpeech

    fun reset() { ring.clear(); utter.clear(); inSpeech = false; aboveRun = 0; silenceRun = 0; speechFrames = 0 }

    fun levelDb(frame: ShortArray): Float {
        var sum = 0.0
        for (s in frame) { val v = s / 32768.0; sum += v * v }
        val rms = sqrt(sum / max(1, frame.size))
        return if (rms < 1e-6) -120f else (20 * log10(rms)).toFloat()
    }

    /** @param assistantSpeaking true while ULTRON's own audio is playing (raises the start threshold). */
    fun feed(frame: ShortArray, assistantSpeaking: Boolean = false): List<VadEvent> {
        val out = ArrayList<VadEvent>(1)
        val db = levelDb(frame)
        lastLevelDb = db
        val extra = if (assistantSpeaking) cfg.bargeInExtraDb else 0f
        val startThr = max(noiseFloorDb + cfg.startMarginDb + extra, cfg.minAbsoluteDb + extra)
        val endThr = max(noiseFloorDb + cfg.endMarginDb + extra, cfg.minAbsoluteDb - 3f + extra)

        if (!inSpeech) {
            if (db > startThr) aboveRun++ else {
                aboveRun = 0
                // Learn the room: slow rise, faster fall, never from speech.
                noiseFloorDb += (db - noiseFloorDb) * (if (db < noiseFloorDb) 0.1f else 0.02f)
                noiseFloorDb = noiseFloorDb.coerceIn(-75f, -30f)
            }
            ring.addLast(frame.copyOf())
            while (ring.size > preRollFrames + cfg.startFrames) ring.removeFirst()
            if (aboveRun >= cfg.startFrames) {
                inSpeech = true
                silenceRun = 0
                speechFrames = aboveRun
                utter.clear()
                utter.addAll(ring)
                ring.clear()
                out += VadEvent.SpeechStart
            }
            return out
        }

        utter.add(frame.copyOf())
        if (db > endThr) { silenceRun = 0; speechFrames++ } else silenceRun++
        val ended = silenceRun >= endFrames
        if (ended || utter.size >= maxFrames) {
            if (speechFrames >= minFrames) {
                val total = utter.size - (if (ended) max(0, silenceRun - 2) else 0)
                val pcm = ShortArray(total * frameSamples)
                var p = 0
                for (i in 0 until total) { val f = utter[i]; System.arraycopy(f, 0, pcm, p, f.size); p += f.size }
                out += VadEvent.Utterance(pcm, total * cfg.frameMs)
            } else out += VadEvent.Rejected
            utter.clear(); inSpeech = false; aboveRun = 0; silenceRun = 0; speechFrames = 0
        }
        return out
    }
}
