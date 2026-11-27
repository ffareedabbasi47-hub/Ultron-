package com.ultron.ai.domain.live

import org.junit.Assert.*
import org.junit.Test

class LiveTurnAssemblerTest {
    @Test fun spokenTurnGivesOneUserAndOneAssistantMessage() {
        val a = LiveTurnAssembler()
        assertTrue(a.feed("Hello ", null, false, false).isEmpty())
        assertTrue(a.feed("ULTRON", null, false, false).isEmpty())
        val m1 = a.feed(null, "Yes ", false, false)
        assertEquals(listOf(LiveTurnAssembler.Msg("user", "Hello ULTRON")), m1)
        assertTrue(a.feed(null, "Boss?", false, false).isEmpty())
        assertEquals(listOf(LiveTurnAssembler.Msg("assistant", "Yes Boss?")), a.feed(null, null, true, false))
    }
    @Test fun typedTurnHasOnlyAssistantMessage() { // typed text is saved by the chat; Live sends no input transcript for it
        val a = LiveTurnAssembler()
        a.feed(null, "Aapne Hello bola tha.", false, false)
        assertEquals(listOf(LiveTurnAssembler.Msg("assistant", "Aapne Hello bola tha.")), a.feed(null, null, true, false))
    }
    @Test fun noDuplicateOnSecondCompleteAndNothingInvented() {
        val a = LiveTurnAssembler()
        a.feed(null, "ok", false, false)
        assertEquals(1, a.feed(null, null, true, false).size)
        assertTrue(a.feed(null, null, true, false).isEmpty())
        assertTrue(a.feed(null, null, false, true).isEmpty()) // audio-only turn: no text -> no bubble
    }
    @Test fun interruptionFlushesPartialAnswerOnce() {
        val a = LiveTurnAssembler()
        a.feed(null, "Main bata raha", false, false)
        assertEquals("Main bata raha", a.feed(null, null, false, true).single().text)
        assertTrue(a.feed(null, null, true, false).isEmpty())
    }
}
