package com.ultron.ai.domain

import com.ultron.ai.data.ai.GeminiWire
import com.ultron.ai.data.ai.ThinkingMode
import com.ultron.ai.domain.live.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControlTest {
    @Test fun navigationCommands() {
        assertEquals(UltronAction.Navigate(NavKind.BACK), CommandParser.parse("back jao"))
        assertEquals(UltronAction.Navigate(NavKind.HOME), CommandParser.parse("Ultron home jao"))
        assertEquals(UltronAction.Navigate(NavKind.RECENTS), CommandParser.parse("recent apps dikhao"))
        assertEquals(UltronAction.Navigate(NavKind.NOTIFICATIONS), CommandParser.parse("notification panel"))
    }
    @Test fun scrollCommands() {
        assertEquals(UltronAction.Scroll(true), CommandParser.parse("scroll down"))
        assertEquals(UltronAction.Scroll(true), CommandParser.parse("neeche scroll karo"))
        assertEquals(UltronAction.Scroll(false), CommandParser.parse("upar scroll"))
    }
    @Test fun clickCommands() {
        assertEquals(UltronAction.Click("search"), CommandParser.parse("click search"))
        assertEquals(UltronAction.Click("play"), CommandParser.parse("play pe click karo"))
        assertEquals(UltronAction.Click("subscribe"), CommandParser.parse("subscribe dabao"))
    }
    @Test fun typeAndRead() {
        assertEquals(UltronAction.TypeText("hello bhai"), CommandParser.parse("type hello bhai"))
        assertEquals(UltronAction.ReadScreen, CommandParser.parse("screen par kya hai"))
    }
    @Test fun screenTask() {
        assertEquals(UltronAction.ScreenTask("whatsapp khol ke rahul ko hi bhej"), CommandParser.parse("agent: whatsapp khol ke rahul ko hi bhej"))
        assertEquals(UltronAction.ScreenTask("wifi band"), CommandParser.parse("phone mein wifi band kar do"))
    }
    @Test fun oldCommandsStillWork() {
        assertEquals(UltronAction.OpenApp("whatsapp"), CommandParser.parse("whatsapp kholo"))
        assertTrue(CommandParser.parse("youtube pe arijit singh chalao") is UltronAction.YouTubeSearch)
        assertNull(CommandParser.parse("photosynthesis kya hota hai"))
    }
    @Test fun validatorBounds() {
        assertFalse(ActionValidator.validate(UltronAction.Click("")).ok)
        assertFalse(ActionValidator.validate(UltronAction.TypeText("x".repeat(400))).ok)
        assertTrue(ActionValidator.validate(UltronAction.ScreenTask("kuch")).ok)
    }

    @Test fun riskyLabelsNeedConfirmation() {
        for (l in listOf("Send", "Pay now", "Place order", "Delete chat", "Call", "Confirm", "₹ 499", "Uninstall")) assertTrue(l, RiskPolicy.needsConfirmation(l))
        for (l in listOf("Search", "Play", "Next", "Settings", "Subscribe")) assertFalse(l, RiskPolicy.needsConfirmation(l))
        assertTrue(RiskPolicy.packageBlocked("com.google.android.packageinstaller"))
    }

    @Test fun agentJsonParsing() {
        assertEquals(AgentStep.Click(12, null), AgentProtocol.parse("{\"action\":\"click\",\"id\":12}"))
        assertEquals(AgentStep.Click(3, null), AgentProtocol.parse("Sure!\n```json\n{\"action\":\"click\",\"id\":3.0}\n```"))
        assertEquals(AgentStep.Type("hi"), AgentProtocol.parse("{\"action\":\"type\",\"text\":\"hi\"}"))
        assertEquals(AgentStep.Scroll(false), AgentProtocol.parse("{\"action\":\"scroll\",\"dir\":\"up\"}"))
        assertEquals(AgentStep.Open("youtube"), AgentProtocol.parse("{\"action\":\"open\",\"app\":\"youtube\"}"))
        assertTrue(AgentProtocol.parse("{\"action\":\"done\",\"message\":\"ho gaya\"}") is AgentStep.Done)
        assertTrue(AgentProtocol.parse("not json") is AgentStep.Fail)
        assertTrue(AgentProtocol.parse("{\"action\":\"rm -rf\"}") is AgentStep.Fail)
        assertTrue(AgentProtocol.parse("{\"action\":\"type\",\"text\":\"\"}") is AgentStep.Fail)
    }
    @Test fun yesNoAnswers() {
        assertEquals(true, AgentProtocol.yesNo("haan kar do"))
        assertEquals(true, AgentProtocol.yesNo("Yes."))
        assertEquals(false, AgentProtocol.yesNo("nahi ruk"))
        assertNull(AgentProtocol.yesNo("pata nahi shayad"))
    }
    @Test fun snapshotDescribesNodes() {
        val d = ScreenSnapshot("com.x", listOf(ScreenNode(0, "Search", true, false, false), ScreenNode(1, "Box", false, true, false))).describe()
        assertTrue(d.contains("[0] Search (tap)")); assertTrue(d.contains("[1] Box (input)"))
    }

    // ---- Gemini audio / TTS wire
    @Test fun transcribeBodyHasInlineAudioAndPrompt() {
        val b = GeminiWire.transcribeBody("QUJD", ThinkingMode.NONE)
        assertTrue(b.contains("\"inline_data\"")); assertTrue(b.contains("audio/wav")); assertTrue(b.contains("QUJD")); assertTrue(b.contains("[none]"))
    }
    @Test fun transcriptCleaning() {
        assertNull(GeminiWire.cleanTranscript("[none]"))
        assertNull(GeminiWire.cleanTranscript("  "))
        assertEquals("youtube kholo", GeminiWire.cleanTranscript("\"youtube kholo\"\n"))
    }
    @Test fun parseTextSkipsThoughts() {
        val body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"secret\",\"thought\":true},{\"text\":\"namaste\"}]}}]}"
        assertEquals("namaste", GeminiWire.parseText(body))
    }
    @Test fun ttsBodyShape() {
        val b = GeminiWire.ttsBody("Hello \"world\"", "Algenib")
        assertTrue(b.contains("\"AUDIO\"")); assertTrue(b.contains("\"voiceName\":\"Algenib\"")); assertTrue(b.contains("prebuiltVoiceConfig"))
        assertTrue(b.contains("Hello \\\"world\\\""))
    }
    @Test fun parseTtsAudio() {
        val body = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/L16;codec=pcm;rate=24000\",\"data\":\"AAEC\"}}]}}]}"
        val a = GeminiWire.parseTtsAudio(body)!!
        assertEquals("AAEC", a.base64); assertEquals(24000, a.sampleRate)
        assertNull(GeminiWire.parseTtsAudio("{\"candidates\":[]}"))
    }
    @Test fun wavHeaderAndPcm() {
        val w = Wav.wrap(shortArrayOf(1, -2, 300), 16000)
        assertEquals(44 + 6, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(6, (w[40].toInt() and 0xFF)) // data length
        val pcm = Wav.pcmFromBytes(w.copyOfRange(44, w.size))
        assertEquals(listOf<Short>(1, -2, 300), pcm.toList())
    }
    @Test fun cloudVoicesAreNamed() { assertNotNull(GeminiWire.CLOUD_VOICES.firstOrNull { it.first == "Algenib" }) }
}
