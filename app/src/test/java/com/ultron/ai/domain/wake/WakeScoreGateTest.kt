package com.ultron.ai.domain.wake

import org.junit.Assert.*
import org.junit.Test

class WakeScoreGateTest {
    @Test fun oneTriggerPerUtterance() {
        val g = WakeScoreGate(0.5f, 2, 2500)
        var hits = 0; var t = 0L
        listOf(0.1f, 0.7f, 0.9f, 0.95f, 0.9f, 0.8f).forEach { if (g.onScore(it, t)) hits++; t += 80 }
        assertEquals(1, hits)
    }
    @Test fun singleSpikeIgnored() {
        val g = WakeScoreGate(0.5f, 2, 2500)
        assertFalse(g.onScore(0.9f, 0)); assertFalse(g.onScore(0.1f, 80)); assertFalse(g.onScore(0.9f, 160))
    }
    @Test fun unrelatedSpeechNeverTriggers() {
        val g = WakeScoreGate(0.5f, 2, 2500)
        var t = 0L
        repeat(500) { assertFalse(g.onScore(0.2f + (it % 5) * 0.05f, t)); t += 80 }
    }
    @Test fun triggersAgainAfterDebounce() {
        val g = WakeScoreGate(0.5f, 1, 2500)
        assertTrue(g.onScore(0.9f, 0)); assertFalse(g.onScore(0.9f, 1000)); assertTrue(g.onScore(0.9f, 3000))
    }
    @Test fun sensitivityMapsToThreshold() {
        assertEquals(0.5f, WakeScoreGate.thresholdFor(1f), 0.001f)
        assertTrue(WakeScoreGate.thresholdFor(2f) < WakeScoreGate.thresholdFor(0.5f))
    }
    @Test fun chunkBufferSlicesExactly() {
        val b = ChunkBuffer(1280); var n = 0
        repeat(10) { b.push(ShortArray(512)) { c -> assertEquals(1280, c.size); n++ } }
        assertEquals(4, n) // 5120 samples = 4 chunks
    }
}
