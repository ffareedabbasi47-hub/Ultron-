package com.ultron.ai.domain.live

import org.junit.Assert.*
import org.junit.Test

class LiveWireTypedTest {
    @Test fun typedTextIsRealtimeInput() {
        assertEquals("""{"realtimeInput":{"text":"What did I just ask?"}}""", LiveWire.text("What did I just ask?"))
    }
    @Test fun historyIsNotATurn() {
        val h = LiveWire.history(listOf("user" to "Hello", "model" to "Yes Boss?"))
        assertTrue(h.contains("\"turnComplete\":false"))
        assertTrue(h.contains("\"role\":\"model\""))
    }
}
