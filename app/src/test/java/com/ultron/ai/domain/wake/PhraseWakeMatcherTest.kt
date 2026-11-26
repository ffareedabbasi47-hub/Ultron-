package com.ultron.ai.domain.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three synthetic "phrases" (different syllable sequences) stand in for Hey / Hello / Wake up ULTRON. They check the
 * decision logic: each phrase is recognised as ITSELF, other speech/noise is rejected, a trailing command is ignored,
 * ambiguity and cooldown rules, sensitivity and storage. Real-voice accuracy can only be measured on a device.
 */
class PhraseWakeMatcherTest {
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

    private val hey = listOf(500.0 to 1500.0, 700.0 to 1100.0, 350.0 to 900.0, 600.0 to 2200.0)
    private val hello = listOf(420.0 to 2300.0, 800.0 to 1000.0, 300.0 to 1700.0, 650.0 to 1200.0, 380.0 to 2600.0)
    private val wakeUp = listOf(900.0 to 2000.0, 300.0 to 800.0, 550.0 to 1900.0, 750.0 to 1300.0, 450.0 to 2400.0, 280.0 to 1100.0)
    private val unrelated = listOf(1200.0 to 2900.0, 250.0 to 600.0, 1000.0 to 1500.0, 330.0 to 2100.0)

    private fun feats(p: ShortArray) = Mfcc.computeRaw(p)
    private fun tpl(freqs: List<Pair<Double, Double>>, base: Long) =
        (1..4).map { WakeTemplate(Mfcc.compute(word(freqs, 1.0 + (it - 2.5) * 0.04, base + it))) }
    private fun sets() = mapOf(WakePhrase.HEY to tpl(hey, 100), WakePhrase.HELLO to tpl(hello, 200), WakePhrase.WAKE_UP to tpl(wakeUp, 300))

    @Test fun heyUltronIsRecognisedAsHey() {
        val d = PhraseWakeMatcher.decide(sets(), feats(word(hey, 1.0, 901)), 1f)
        assertEquals(d.note, WakePhrase.HEY, d.phrase)
    }
    @Test fun helloUltronIsRecognisedAsHello() {
        val d = PhraseWakeMatcher.decide(sets(), feats(word(hello, 1.0, 902)), 1f)
        assertEquals(d.note, WakePhrase.HELLO, d.phrase)
    }
    @Test fun wakeUpUltronIsRecognisedAsWakeUp() {
        val d = PhraseWakeMatcher.decide(sets(), feats(word(wakeUp, 1.0, 903)), 1f)
        assertEquals(d.note, WakePhrase.WAKE_UP, d.phrase)
        assertEquals("I'm awake, Boss.", d.phrase!!.greeting)
    }
    @Test fun heyAndHelloBothAnswerYesBoss() {
        assertEquals("Yes Boss?", WakePhrase.HEY.greeting)
        assertEquals("Yes Boss?", WakePhrase.HELLO.greeting)
    }
    @Test fun eachPhraseWorksAtOtherSpeeds() {
        val s = sets()
        for ((i, sp) in listOf(0.88, 1.0, 1.15).withIndex()) {
            assertEquals(WakePhrase.HEY, PhraseWakeMatcher.decide(s, feats(word(hey, sp, 910L + i)), 1f).phrase)
            assertEquals(WakePhrase.HELLO, PhraseWakeMatcher.decide(s, feats(word(hello, sp, 920L + i)), 1f).phrase)
            assertEquals(WakePhrase.WAKE_UP, PhraseWakeMatcher.decide(s, feats(word(wakeUp, sp, 930L + i)), 1f).phrase)
        }
    }
    @Test fun trailingCommandIsIgnoredForEveryPhrase() {
        val s = sets()
        assertEquals(WakePhrase.HEY, PhraseWakeMatcher.decide(s, feats(word(hey, 1.0, 940) + word(unrelated, 1.0, 941)), 1f).phrase)
        assertEquals(WakePhrase.HELLO, PhraseWakeMatcher.decide(s, feats(word(hello, 1.0, 942) + word(unrelated, 1.0, 943)), 1f).phrase)
        assertEquals(WakePhrase.WAKE_UP, PhraseWakeMatcher.decide(s, feats(word(wakeUp, 1.0, 944) + word(unrelated, 1.0, 945)), 1f).phrase)
    }
    @Test fun otherSpeechIsRejected() {
        val d = PhraseWakeMatcher.decide(sets(), feats(word(unrelated, 1.0, 950)), 1f)
        assertNull(d.note, d.phrase)
    }
    @Test fun noiseIsRejected() {
        val r = Random(5); val noise = ShortArray(rate) { (r.nextGaussian() * 3000).toInt().toShort() }
        assertNull(PhraseWakeMatcher.decide(sets(), feats(noise), 1f).phrase)
    }
    @Test fun longConversationIsSkippedBeforeAnyMatching() {
        var long = ShortArray(0)
        repeat(8) { long += word(unrelated, 1.0, 960L + it) }
        val d = PhraseWakeMatcher.decide(sets(), feats(long), 1f)
        assertNull(d.phrase); assertEquals("too long", d.note)
        assertTrue(PhraseWakeMatcher.maxUtteranceMs(sets()) in 500L..12_000L)
    }
    @Test fun phraseWithTooFewTemplatesIsNotUsed() {
        val partial = mapOf(WakePhrase.HEY to tpl(hey, 100).take(2))
        assertFalse(PhraseWakeMatcher.ready(partial))
        assertNull(PhraseWakeMatcher.decide(partial, feats(word(hey, 1.0, 970)), 1f).phrase)
    }
    @Test fun onlyEnrolledPhrasesCanFire() {
        val onlyHey = mapOf(WakePhrase.HEY to tpl(hey, 100))
        assertEquals(WakePhrase.HEY, PhraseWakeMatcher.decide(onlyHey, feats(word(hey, 1.0, 971)), 1f).phrase)
        assertNull(PhraseWakeMatcher.decide(onlyHey, feats(word(wakeUp, 1.0, 972)), 1f).phrase)
    }
    @Test fun lowSensitivityIsStricterThanHigh() {
        val s = sets(); val f = feats(word(hey, 1.0, 973))
        val lo = PhraseWakeMatcher.decide(s, f, 0.5f); val hi = PhraseWakeMatcher.decide(s, f, 2f)
        assertTrue(hi.threshold > lo.threshold)
    }
    @Test fun storageRoundTripKeepsEveryPhrase() {
        val s = sets()
        val back = WakePhraseStore.deserialize(WakePhraseStore.serialize(s))
        assertEquals(s.keys, back.keys)
        for (k in s.keys) { assertEquals(s[k]!!.size, back[k]!!.size); assertEquals(s[k]!![0].features.size, back[k]!![0].features.size) }
        assertTrue(WakePhraseStore.deserialize(ByteArray(12)).isEmpty())
        assertTrue(WakePhraseStore.deserialize(ByteArray(0)).isEmpty())
    }
    @Test fun cooldownBlocksDoubleFire() {
        val c = WakeCooldown(1500)
        assertTrue(c.allow(1000)); assertFalse(c.allow(1500)); assertFalse(c.allow(2400)); assertTrue(c.allow(2600))
    }
}
