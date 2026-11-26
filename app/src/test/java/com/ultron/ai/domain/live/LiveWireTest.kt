package com.ultron.ai.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWireTest {
    @Test fun setupHasTextModalityAndManualActivity() {
        val s = LiveWire.setup()
        assertTrue(s.contains("\"setup\"")); assertTrue(s.contains("gemini-3.5-transcribe-live")); assertTrue(s.contains("\"TEXT\""))
        assertTrue(s.contains("\"disabled\":true")); assertTrue(s.contains("inputAudioTranscription"))
    }
    @Test fun audioMessageShape() {
        val a = LiveWire.audio("QUJD")
        assertTrue(a.contains("\"realtimeInput\"")); assertTrue(a.contains("audio/pcm;rate=16000")); assertTrue(a.contains("QUJD"))
    }
    @Test fun parsesSetupComplete() = assertTrue(LiveWire.parse("""{"setupComplete":{}}""")!!.setupComplete)
    @Test fun parsesFinalAndInterim() {
        assertEquals("youtube kholo", LiveWire.parse("""{"serverContent":{"inputTranscription":{"text":"youtube kholo"}}}""")!!.finalText)
        assertEquals("you", LiveWire.parse("""{"serverContent":{"interimInputTranscription":{"text":"you"}}}""")!!.interimText)
    }
    @Test fun parsesTurnCompleteAndError() {
        assertTrue(LiveWire.parse("""{"serverContent":{"turnComplete":true}}""")!!.turnComplete)
        assertEquals("quota", LiveWire.parse("""{"error":{"message":"quota"}}""")!!.error)
    }
    @Test fun garbageIsNull() { assertNull(LiveWire.parse("not json")); assertNotNull(LiveWire.parse("""{"x":1}""")) }
    @Test fun devanagariDetected() { assertTrue(LiveWire.hasDevanagari("यूट्यूब खोलो")); assertFalse(LiveWire.hasDevanagari("youtube kholo")) }
}

class LiveCallWireTest {
    @Test fun setupCallHasAudioVoiceToolsAndInterrupt() {
        val s = LiveWire.setupCall("You are ULTRON", "Charon")
        assertTrue(s.contains("gemini-3.8-live")); assertTrue(s.contains("\"AUDIO\"")); assertTrue(s.contains("\"voiceName\":\"Charon\""))
        assertTrue(s.contains("START_OF_ACTIVITY_INTERRUPTS")); assertTrue(s.contains("open_app")); assertTrue(s.contains("youtube_search")); assertTrue(s.contains("inputAudioTranscription"))
    }
    @Test fun parsesAudioChunksAndFlags() {
        val m = LiveWire.parseCall("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"turnComplete":true,"outputTranscription":{"text":"hello"}}}""")!!
        assertEquals(listOf("AAA="), m.audio); assertTrue(m.turnComplete); assertEquals("hello", m.said)
    }
    @Test fun parsesInterruptedAndHeard() {
        val m = LiveWire.parseCall("""{"serverContent":{"interrupted":true,"inputTranscription":{"text":"ruk"}}}""")!!
        assertTrue(m.interrupted); assertEquals("ruk", m.heard)
    }
    @Test fun parsesToolCall() {
        val m = LiveWire.parseCall("""{"toolCall":{"functionCalls":[{"id":"1","name":"open_app","args":{"name":"YouTube"}}]}}""")!!
        assertEquals(1, m.tools.size); assertEquals("open_app", m.tools[0].name); assertEquals("YouTube", m.tools[0].args["name"])
    }
    @Test fun toolResponseShape() { val r = LiveWire.toolResponse("1", "open_app", "ok"); assertTrue(r.contains("functionResponses") && r.contains("\"id\":\"1\"")) }
    @Test fun setupAndGoAway() { assertTrue(LiveWire.parseCall("""{"setupComplete":{}}""")!!.setupComplete); assertTrue(LiveWire.parseCall("""{"goAway":{"timeLeft":"5s"}}""")!!.goAway) }
}
