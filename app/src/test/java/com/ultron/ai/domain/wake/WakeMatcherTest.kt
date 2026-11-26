package com.ultron.ai.domain.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthetic "words" (sequences of multi-tone syllables) stand in for speech: these tests check the DTW logic
 * (time-warp tolerance, rejection of other words, ignoring trailing speech, storage). They do NOT measure
 * real-voice accuracy, which can only be checked on a device.
 */
class WakeMatcherTest {
    private val rate = 16000
    private fun syllable(f1: Double, f2: Double, ms: Int, amp: Double, rnd: Random): ShortArray {
        val n = rate * ms / 1000
        return ShortArray(n) { i ->
            val env = sin(PI * i / n)
            val s = (sin(2 * PI * f1 * i / rate) + 0.6 * sin(2 * PI * f2 * i / rate)) * env * amp + rnd.nextGaussian() * 0.004
            (s * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
    private fun word(freqs: List<Pair<Double, Double>>, speed: Double, seed: Long, jitter: Double = 0.03): ShortArray {
        val rnd = Random(seed)
        var out = ShortArray(rate / 10) { (rnd.nextGaussian() * 130).toInt().toShort() }
        for ((a, b) in freqs) {
            val j = 1 + (rnd.nextDouble() - 0.5) * 2 * jitter
            out += syllable(a * j, b * j, (170 / speed).toInt(), 0.25, rnd)
        }
        return out + ShortArray(rate / 10) { (rnd.nextGaussian() * 130).toInt().toShort() }
    }
    private val heyUltron = listOf(500.0 to 1500.0, 700.0 to 1100.0, 350.0 to 900.0, 600.0 to 2200.0)
    private val other = listOf(300.0 to 2500.0, 900.0 to 1300.0, 450.0 to 700.0, 800.0 to 2000.0, 380.0 to 1700.0)
    private fun feats(p: ShortArray) = Mfcc.compute(p)
    private fun templates() = (1..4).map { WakeTemplate(feats(word(heyUltron, 1.0 + (it - 2.5) * 0.04, it.toLong()))) }

    @Test fun mfccShape() {
        val f = feats(word(heyUltron, 1.0, 1))
        assertTrue(f.size > 50); assertEquals(Mfcc.DIM, f[0].size)
        assertTrue(f.all { fr -> fr.all { it.isFinite() } })
    }
    @Test fun sameWordAtOtherSpeedsMatches() {
        val ts = templates(); val thr = WakeMatcher.threshold(ts, 1f)
        for ((i, sp) in listOf(0.85, 1.0, 1.2).withIndex()) {
            val s = WakeMatcher.score(ts, feats(word(heyUltron, sp, 50L + i)))
            assertTrue("speed $sp score $s thr $thr", s < thr)
        }
    }
    @Test fun differentWordIsRejected() {
        val ts = templates(); val thr = WakeMatcher.threshold(ts, 1f)
        val s = WakeMatcher.score(ts, feats(word(other, 1.0, 77)))
        assertTrue("score $s thr $thr", s > thr)
    }
    @Test fun noiseIsRejected() {
        val ts = templates(); val thr = WakeMatcher.threshold(ts, 1f)
        val r = Random(5); val noise = ShortArray(rate) { (r.nextGaussian() * 3000).toInt().toShort() }
        assertTrue(WakeMatcher.score(ts, feats(noise)) > thr)
    }
    @Test fun trailingCommandIsIgnored() {
        val ts = templates(); val thr = WakeMatcher.threshold(ts, 1f)
        val s = WakeMatcher.score(ts, feats(word(heyUltron, 1.0, 60) + word(other, 1.0, 61)))
        assertTrue("score $s thr $thr", s < thr)
    }
    @Test fun sensitivityMovesThreshold() {
        val ts = templates()
        assertTrue(WakeMatcher.threshold(ts, 1.5f) > WakeMatcher.threshold(ts, 0.7f))
    }
    @Test fun storageRoundTrip() {
        val ts = templates()
        val back = WakeMatcher.deserialize(WakeMatcher.serialize(ts))
        assertEquals(ts.size, back.size)
        assertEquals(ts[0].features.size, back[0].features.size)
        assertEquals(ts[1].features[3][2], back[1].features[3][2])
        assertTrue(WakeMatcher.deserialize(ByteArray(8)).isEmpty())
    }
}
