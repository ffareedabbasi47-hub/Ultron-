package com.ultron.ai.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LiveCoreTest {
    private val cfg = VadConfig()
    private val n = cfg.sampleRate * cfg.frameMs / 1000

    private fun tone(amp: Double, phase: Int = 0) = ShortArray(n) { i -> (amp * 32767 * sin(2 * PI * 220 * (i + phase) / cfg.sampleRate)).toInt().toShort() }
    private fun quiet() = tone(0.0005)
    private fun speech() = tone(0.2)

    private fun run(v: VadSegmenter, frames: List<ShortArray>, speaking: Boolean = false): List<VadEvent> = frames.flatMap { v.feed(it, speaking) }

    @Test fun silenceNeverTriggers() {
        val ev = run(VadSegmenter(cfg), List(500) { quiet() })
        assertTrue(ev.isEmpty())
    }

    @Test fun speechProducesOneUtteranceWithPreRoll() {
        val v = VadSegmenter(cfg)
        val ev = run(v, List(50) { quiet() } + List(50) { speech() } + List(60) { quiet() })
        assertEquals(1, ev.count { it is VadEvent.SpeechStart })
        val u = ev.filterIsInstance<VadEvent.Utterance>().single()
        assertTrue("includes speech 1s", u.durationMs >= 1000)
        assertTrue("includes pre-roll (<1.6s total)", u.durationMs <= 1600)
    }

    @Test fun shortPauseInsideSentenceDoesNotSplit() {
        val v = VadSegmenter(cfg)
        // 2 s speech, 400 ms gap (< 700 ms end pause), 2 s speech, then silence
        val ev = run(v, List(30) { quiet() } + List(100) { speech() } + List(20) { quiet() } + List(100) { speech() } + List(60) { quiet() })
        assertEquals(1, ev.filterIsInstance<VadEvent.Utterance>().size)
    }

    @Test fun longPauseSplitsUtterances() {
        val v = VadSegmenter(cfg)
        val ev = run(v, List(30) { quiet() } + List(60) { speech() } + List(60) { quiet() } + List(60) { speech() } + List(60) { quiet() })
        assertEquals(2, ev.filterIsInstance<VadEvent.Utterance>().size)
    }

    @Test fun shortWordHaanIsKept() {
        val v = VadSegmenter(cfg)
        val ev = run(v, List(30) { quiet() } + List(12) { speech() } + List(60) { quiet() }) // 240 ms
        assertEquals(1, ev.filterIsInstance<VadEvent.Utterance>().size)
    }

    @Test fun singleClickIsRejectedNotTranscribed() {
        val v = VadSegmenter(cfg)
        val ev = run(v, List(30) { quiet() } + List(3) { speech() } + List(60) { quiet() })
        assertTrue(ev.none { it is VadEvent.Utterance })
    }

    @Test fun hysteresisKeepsSpeechThroughQuietPatch() {
        val v = VadSegmenter(cfg)
        // speech, then softer-but-still-speech (between end and start threshold) must not end the utterance early
        val soft = tone(0.03)
        val ev = run(v, List(30) { quiet() } + List(40) { speech() } + List(30) { soft } + List(40) { speech() } + List(60) { quiet() })
        assertEquals(1, ev.filterIsInstance<VadEvent.Utterance>().size)
    }

    @Test fun maxUtteranceCapsBuffer() {
        val v = VadSegmenter(cfg.copy(maxUtteranceMs = 3000))
        val ev = run(v, List(30) { quiet() } + List(400) { speech() })
        assertTrue(ev.filterIsInstance<VadEvent.Utterance>().all { it.durationMs <= 3000 })
    }

    @Test fun bargeInNeedsLouderSoundWhileAssistantSpeaks() {
        val moderate = tone(0.007) // about -46 dB: above normal start, below start + barge-in margin
        val normal = run(VadSegmenter(cfg), List(40) { quiet() } + List(40) { moderate } + List(60) { quiet() })
        val speaking = run(VadSegmenter(cfg), List(40) { quiet() } + List(40) { moderate } + List(60) { quiet() }, speaking = true)
        assertTrue(normal.any { it is VadEvent.Utterance })
        assertTrue(speaking.none { it is VadEvent.Utterance })
        val loud = run(VadSegmenter(cfg), List(40) { quiet() } + List(40) { tone(0.4) } + List(60) { quiet() }, speaking = true)
        assertTrue(loud.any { it is VadEvent.Utterance })
    }

    @Test fun noiseFloorAdaptsToNoisyRoom() {
        val v = VadSegmenter(cfg)
        val hum = tone(0.004)
        run(v, List(400) { hum })
        assertTrue(v.noiseFloorDb > -60f)
        assertTrue(run(v, List(100) { hum }).isEmpty())
    }

    // ---- sleep clock
    @Test fun sleepFiresAfterTimeoutOfRealSilence() {
        val c = SleepClock(SleepTimeout.M5.millis)
        c.reset(0)
        assertFalse(c.tick(4 * 60_000L, false, false))
        assertTrue(c.tick(5 * 60_000L, false, false))
    }
    @Test fun userSpeechResetsTimer() {
        val c = SleepClock(SleepTimeout.M5.millis)
        c.reset(0)
        c.tick(4 * 60_000L, true, false)
        assertFalse(c.tick(8 * 60_000L, false, false))
        assertTrue(c.tick(9 * 60_000L + 1, false, false))
    }
    @Test fun busyPausesTimer() {
        val c = SleepClock(SleepTimeout.M10.millis)
        c.reset(0)
        // ULTRON thinking/speaking for 30 minutes must not put the session to sleep
        for (t in 0..30 step 1) assertFalse(c.tick(t * 60_000L, false, true))
        assertFalse(c.tick(31 * 60_000L, false, false))
    }
    @Test fun offNeverSleeps() {
        val c = SleepClock(SleepTimeout.OFF.millis)
        c.reset(0)
        assertFalse(c.tick(10_000_000_000L, false, false))
        assertNull(c.remainingMs(1))
    }
    @Test fun allOptionsMapToMinutes() {
        assertEquals(listOf(0, 5, 10, 15, 20), SleepTimeout.entries.map { it.minutes })
    }

    // ---- state machine
    @Test fun transitionsRejectNonsense() {
        assertFalse(LiveTransitions.canMove(LiveState.STOPPED, LiveState.SPEAKING))
        assertTrue(LiveTransitions.canMove(LiveState.STOPPED, LiveState.STARTING))
        assertTrue(LiveTransitions.canMove(LiveState.SPEAKING, LiveState.USER_SPEECH)) // barge-in
        assertTrue(LiveTransitions.canMove(LiveState.LISTENING, LiveState.SLEEP))
        assertFalse(LiveTransitions.canMove(LiveState.SLEEP, LiveState.SPEAKING))
    }
    @Test fun everyActiveStateCanStop() {
        for (s in LiveState.entries) if (s != LiveState.STOPPED) assertTrue(s.name, LiveTransitions.canMove(s, LiveState.STOPPED))
    }

    // ---- echo gate
    @Test fun gateDiscardsOwnVoiceWithoutAec() {
        val g = TtsEchoGate(450)
        g.onSpeechStart()
        assertTrue(g.shouldDiscard(1000, 1200, aecEffective = false))
        g.onSpeechEnd(2000)
        assertTrue(g.shouldDiscard(2200, 2500, aecEffective = false))  // tail
        assertFalse(g.shouldDiscard(2600, 3000, aecEffective = false)) // after tail
    }
    @Test fun gateTrustsAecWhenEffective() {
        val g = TtsEchoGate(450); g.onSpeechStart()
        assertFalse(g.shouldDiscard(1000, 1200, aecEffective = true))
    }

    // ---- wake word
    @Test fun wakePhrases() {
        assertEquals("", WakeWord.match("Hey Ultron"))
        assertEquals("youtube kholo", WakeWord.match("hello ultron, youtube kholo"))
        assertEquals("", WakeWord.match("wake up ultron"))
        assertEquals("time kya hua", WakeWord.match("Ultron time kya hua"))
        assertNull(WakeWord.match("aaj mausam kaisa hai"))
        assertNull(WakeWord.match("mujhe batao ki ultron kya hota hai aur kaun banata hai"))
    }
}

class LipSyncTest {
    private fun tone(amp: Double, hz: Double, ms: Int, rate: Int = 24000) = ShortArray(rate * ms / 1000) { i -> (amp * 32767 * kotlin.math.sin(2 * kotlin.math.PI * hz * i / rate)).toInt().toShort() }

    @Test fun silenceKeepsMouthClosed() {
        val f = LipSync.analyze(ShortArray(24000), 24000)
        assertTrue(f.open.all { it == 0f })
    }
    @Test fun loudSpeechOpensMouthInTimeWithAudio() {
        val pcm = ShortArray(24000 / 2) + tone(0.3, 180.0, 500) + ShortArray(24000 / 2) // 0.5s silence, 0.5s voice, 0.5s silence
        val f = LipSync.analyze(pcm, 24000)
        val (o0, _) = LipSync.at(f, 24000, 6000)   // 0.25 s: silent
        val (o1, _) = LipSync.at(f, 24000, 18000)  // 0.75 s: voice
        val (o2, _) = LipSync.at(f, 24000, 30000)  // 1.25 s: silent again
        assertTrue(o0 < 0.05f); assertTrue(o1 > 0.5f); assertTrue(o2 < 0.15f)
    }
    @Test fun brightSoundsMakeMouthWiderThanLowVowels() {
        val low = LipSync.analyze(tone(0.3, 150.0, 400), 24000)
        val high = LipSync.analyze(tone(0.3, 5500.0, 400), 24000)
        assertTrue(low.shape[10] < high.shape[10])
    }
    @Test fun quietSyllablesStillMove() {
        val f = LipSync.analyze(tone(0.02, 200.0, 300), 24000) // about -37 dB
        assertTrue(f.open[5] > 0.2f)
    }
    @Test fun positionPastEndIsClosed() {
        val f = LipSync.analyze(tone(0.3, 200.0, 100), 24000)
        assertEquals(0f, LipSync.at(f, 24000, 999999).first)
        assertEquals(0f, LipSync.at(f, 24000, -5).first)
    }
}
