package com.ultron.ai.domain.live

import com.ultron.ai.domain.wake.WakePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicAndEdgeTest {
    // ---- one-click / rapid tap behaviour
    @Test fun oneTapStartsFromStopped() {
        assertEquals(MicTapAction.START, MicTapPolicy.action(LiveState.STOPPED, true))
    }
    @Test fun noPermissionAsksInsteadOfStarting() {
        for (s in listOf(LiveState.STOPPED, LiveState.PERMISSION_REQUIRED, LiveState.AUDIO_ERROR, LiveState.PAUSED, LiveState.SLEEP))
            assertEquals(s.name, MicTapAction.REQUEST_PERMISSION, MicTapPolicy.action(s, false))
    }
    @Test fun tapWhileStartingIsIgnored() { assertEquals(MicTapAction.IGNORE, MicTapPolicy.action(LiveState.STARTING, true)) }
    @Test fun tapOnRunningSessionStops() {
        for (s in listOf(LiveState.LISTENING, LiveState.USER_SPEECH, LiveState.THINKING, LiveState.SPEAKING))
            assertEquals(s.name, MicTapAction.STOP, MicTapPolicy.action(s, true))
    }
    @Test fun sleepPausedAndErrorResume() {
        for (s in listOf(LiveState.SLEEP, LiveState.PAUSED, LiveState.AUDIO_ERROR)) assertEquals(MicTapAction.START, MicTapPolicy.action(s, true))
    }
    @Test fun tenRapidTapsProduceExactlyOneAction() {
        val g = TapGuard(700)
        var accepted = 0
        for (i in 0 until 10) if (g.accept(1000L + i * 60L)) accepted++ // 10 taps in 540 ms
        assertEquals(1, accepted)
        assertTrue("a later, deliberate tap is accepted", g.accept(1000L + 540 + 700))
    }
    @Test fun everyStateHasANonCrashingAnswer() {
        for (s in LiveState.entries) { MicTapPolicy.action(s, true); MicTapPolicy.action(s, false) }
    }

    // ---- state machine: nothing can jump out of STOPPED except through STARTING; Stop is always possible
    @Test fun stoppedOnlyGoesToStarting() {
        for (s in LiveState.entries) assertEquals(s.name, s == LiveState.STARTING || s == LiveState.STOPPED, LiveTransitions.canMove(LiveState.STOPPED, s))
    }
    @Test fun startingCannotSkipToSpeaking() {
        assertFalse(LiveTransitions.canMove(LiveState.STARTING, LiveState.SPEAKING))
        assertTrue(LiveTransitions.canMove(LiveState.STARTING, LiveState.LISTENING))
    }
    @Test fun stopIsReachableFromEveryState() {
        for (s in LiveState.entries) assertTrue(s.name, LiveTransitions.canMove(s, LiveState.STOPPED))
    }

    // ---- edge light modes follow the REAL state
    @Test fun edgeModeMapping() {
        assertEquals(EdgeMode.LISTENING, EdgeModes.of(LiveState.LISTENING, true))
        assertEquals(EdgeMode.USER_SPEECH, EdgeModes.of(LiveState.USER_SPEECH, true))
        assertEquals(EdgeMode.THINKING, EdgeModes.of(LiveState.THINKING, true))
        assertEquals(EdgeMode.SPEAKING, EdgeModes.of(LiveState.SPEAKING, true))
        assertEquals(EdgeMode.IDLE, EdgeModes.of(LiveState.SLEEP, true))
    }
    @Test fun noLightWhenMicIsReallyOff() {
        assertEquals(EdgeMode.OFF, EdgeModes.of(LiveState.SLEEP, false))
        for (s in listOf(LiveState.STOPPED, LiveState.STARTING, LiveState.PAUSED, LiveState.PERMISSION_REQUIRED, LiveState.AUDIO_ERROR))
            assertEquals(s.name, EdgeMode.OFF, EdgeModes.of(s, true))
    }
    @Test fun statesLookDifferentAndIdleIsCheapest() {
        val modes = listOf(EdgeMode.IDLE, EdgeMode.LISTENING, EdgeMode.USER_SPEECH, EdgeMode.THINKING, EdgeMode.SPEAKING, EdgeMode.WAKE)
        assertEquals(modes.size, modes.map { Triple(it.periodMs, it.widthDp, it.pulse) }.toSet().size)
        assertTrue(EdgeMode.IDLE.intensity < EdgeMode.LISTENING.intensity)
        assertTrue(EdgeMode.IDLE.fps <= EdgeMode.LISTENING.fps)
        assertTrue(EdgeMode.THINKING.pulse && !EdgeMode.SPEAKING.pulse)
        assertTrue(EdgeMode.SPEAKING.periodMs < EdgeMode.LISTENING.periodMs)
    }

    // ---- wake phrases in text (used when ULTRON is already awake or via the optional cloud check)
    @Test fun threePhrasesAreDistinguished() {
        assertEquals(WakePhrase.HEY, WakeWord.parse("Hey ULTRON")!!.phrase)
        assertEquals(WakePhrase.HELLO, WakeWord.parse("Hello ULTRON")!!.phrase)
        assertEquals(WakePhrase.WAKE_UP, WakeWord.parse("Wake up ULTRON")!!.phrase)
        assertEquals(WakePhrase.WAKE_UP, WakeWord.parse("wake up, Ultron!")!!.phrase)
    }
    @Test fun commandAfterPhraseIsSplitOff() {
        val h = WakeWord.parse("Hey Ultron, open youtube")!!
        assertEquals(WakePhrase.HEY, h.phrase); assertEquals("open youtube", h.rest)
        assertEquals("", WakeWord.parse("hello ultron")!!.rest)
    }
    @Test fun bareUltronIsNotStrippedWhenAwake() {
        assertNull(WakeWord.parse("ultron kaun hai", allowBare = false))
        assertEquals("kaun hai", WakeWord.parse("ultron kaun hai")!!.rest) // asleep path keeps old behaviour
    }
    @Test fun ordinaryTalkIsNotAWake() {
        assertNull(WakeWord.parse("aaj mausam kaisa hai"))
        assertNull(WakeWord.parse("mujhe batao ki ultron kya hota hai aur kaun banata hai"))
        assertNull(WakeWord.parse("hello everyone welcome back to the channel", allowBare = false))
    }
}
