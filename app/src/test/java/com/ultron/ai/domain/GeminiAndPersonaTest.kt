package com.ultron.ai.domain

import com.ultron.ai.data.ai.GeminiEvent
import com.ultron.ai.data.ai.GeminiWire
import com.ultron.ai.data.ai.ThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAndPersonaTest {
    @Test fun personaCarriesIdentityAndRules() {
        val p = Persona.systemPrompt("Fardeen", "short", "hinglish", "chat", "  Hamesha 2 line mein jawab do.  ")
        assertTrue(p.contains("Mujhe Fardeen ne banaya hai."))
        assertTrue(p.contains("Hamesha 2 line mein jawab do."))
        assertTrue(p.contains("Roman Hinglish"))
        assertFalse(Persona.systemPrompt("Fardeen", "short", "hinglish", "chat").contains("standing preferences"))
    }

    @Test fun personaRulesAreLengthLimited() {
        val p = Persona.systemPrompt("F", "short", "english", "chat", "x".repeat(5000))
        assertTrue(p.count { it == 'x' } <= Persona.MAX_RULES_CHARS + 20)
    }

    @Test fun personaUnknownWiresFallBackSafely() {
        val p = Persona.systemPrompt("F", "???", "???", "???")
        assertTrue(p.contains("Roman Hinglish") && p.contains("short and sharp"))
    }

    @Test fun requestBodyShape() {
        val body = GeminiWire.requestBody("SYS", listOf(ChatTurn("user", "hi"), ChatTurn("assistant", "hello"), ChatTurn("user", "q")), 768, ThinkingMode.LEVEL_MINIMAL)
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(body.contains("\"role\":\"model\""))
        assertTrue(body.contains("\"thinkingLevel\":\"minimal\""))
        assertTrue(body.contains("\"maxOutputTokens\":768"))
        assertFalse(GeminiWire.requestBody("S", listOf(ChatTurn("user", "a")), 1, ThinkingMode.NONE).contains("thinkingConfig"))
        assertTrue(GeminiWire.requestBody("S", listOf(ChatTurn("user", "a")), 1, ThinkingMode.BUDGET_ZERO).contains("\"thinkingBudget\":0"))
    }

    @Test fun userTextIsJsonEscaped() {
        val body = GeminiWire.requestBody("S", listOf(ChatTurn("user", "say \"hi\"\nand \\ done")), 10, ThinkingMode.NONE)
        assertTrue(body.contains("say \\\"hi\\\"\\nand \\\\ done"))
    }

    @Test fun thinkingPerModel() {
        assertEquals(ThinkingMode.LEVEL_MINIMAL, GeminiWire.thinkingFor("gemini-3.5-flash-lite"))
        assertEquals(ThinkingMode.LEVEL_MINIMAL, GeminiWire.thinkingFor("gemini-3.8-flash"))
        assertEquals(ThinkingMode.BUDGET_ZERO, GeminiWire.thinkingFor("gemini-2.5-flash"))
        assertEquals(ThinkingMode.NONE, GeminiWire.thinkingFor("gemini-3.1-pro"))
        assertEquals(ThinkingMode.NONE, GeminiWire.thinkingFor("gemini-3.1-flash-image"))
    }

    @Test fun parsesStreamChunks() {
        val line = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Namaste \"},{\"text\":\"Fardeen\"}]}}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3,\"totalTokenCount\":8}}"
        val ev = GeminiWire.parseLine(line)
        assertEquals(GeminiEvent.Delta("Namaste Fardeen"), ev[0])
        assertEquals(GeminiEvent.Usage(5, 3, 8), ev[1])
    }

    @Test fun skipsThoughtPartsAndJunk() {
        val ev = GeminiWire.parseLine("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"secret\",\"thought\":true},{\"text\":\"visible\"}]}}]}")
        assertEquals(listOf<GeminiEvent>(GeminiEvent.Delta("visible")), ev)
        assertTrue(GeminiWire.parseLine("").isEmpty())
        assertTrue(GeminiWire.parseLine(": keep-alive").isEmpty())
        assertTrue(GeminiWire.parseLine("data: not json").isEmpty())
        assertTrue(GeminiWire.parseLine("data: [DONE]").isEmpty())
    }

    @Test fun detectsBlockedPrompt() {
        val ev = GeminiWire.parseLine("data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
        assertEquals(GeminiEvent.Blocked("SAFETY"), ev[0])
    }

    @Test fun listsOnlyChatModels() {
        val body = "{\"models\":[" +
            "{\"name\":\"models/gemini-3.5-flash-lite\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]}," +
            "{\"name\":\"models/gemini-3.8-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
            "{\"name\":\"models/gemini-embedding-001\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
            "{\"name\":\"models/gemini-3.1-flash-image\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
            "{\"name\":\"models/imagen-4\",\"supportedGenerationMethods\":[\"predict\"]}," +
            "{\"name\":\"models/../evil\",\"supportedGenerationMethods\":[\"generateContent\"]}]}"
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), GeminiWire.chatModels(body))
        assertTrue(GeminiWire.chatModels("garbage").isEmpty())
    }

    @Test fun modelIdsCannotInjectPath() {
        assertTrue(GeminiWire.isSafeModelId("gemini-3.5-flash-lite"))
        assertFalse(GeminiWire.isSafeModelId("../x"))
        assertFalse(GeminiWire.isSafeModelId("a/b"))
        assertFalse(GeminiWire.isSafeModelId("a?key=1"))
        assertFalse(GeminiWire.isSafeModelId(""))
    }

    @Test fun firstChunkIsSpokenEarly() {
        val c = SentenceChunker()
        val first = c.feed("Haan bilkul. Ye doosra vaakya thoda lamba hai aur ")
        assertEquals(listOf("Haan bilkul."), first)
    }
}
