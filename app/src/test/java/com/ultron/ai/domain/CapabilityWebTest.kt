package com.ultron.ai.domain

import org.junit.Assert.*
import org.junit.Test

class CapabilityWebTest {
    private fun env(key: Boolean = true, contacts: Boolean = true, wa: Boolean = true) = CapabilityEnv(
        micPermission = true, cloudConsent = key, geminiKey = key, liveCallEnabled = true, wakeReady = false,
        contactsPermission = contacts, callPermission = false, smsPermission = false, whatsappInstalled = wa, accessibilityOn = false, telephony = true,
    )
    private fun st(e: CapabilityEnv, id: String) = CapabilityRegistry.evaluate(e).first { it.cap.id == id }

    @Test fun webSearchUnavailableWithoutKey() {
        val s = st(env(key = false), "web_search")
        assertFalse(s.available)
        assertTrue(CapabilityRegistry.describe(listOf(s)).contains("NAHI"))
    }
    @Test fun webSearchAvailableWithKey() = assertTrue(st(env(), "web_search").available)
    @Test fun whatsappNeedsAppAndContacts() {
        assertEquals(listOf("WhatsApp installed nahi"), st(env(wa = false), "whatsapp").missing)
        assertEquals(listOf("Contacts permission"), st(env(contacts = false), "whatsapp").missing)
        assertTrue(st(env(), "whatsapp").available)
    }
    @Test fun liveIsNotClaimedWithoutKey() {
        assertFalse(st(env(key = false), "live_voice").available)
        assertFalse(st(env(key = false), "text_chat").available)
    }
    @Test fun wakeNeedsRealModelOrRecordings() = assertFalse(st(env(), "wake").available)
    @Test fun answerIsGeneratedFromRegistryOnly() {
        val all = CapabilityRegistry.describe(CapabilityRegistry.evaluate(env(key = false)))
        assertTrue(all.contains("Web search: abhi NAHI chalega"))
        assertEquals("whatsapp", CapabilityRegistry.focusFor("Can you send WhatsApp messages?"))
    }
    @Test fun parsesGroundedAnswerWithSources() {
        val j = """{"candidates":[{"content":{"parts":[{"text":"Result text"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://a.com/x","title":"A"}},{"web":{"uri":"https://a.com/x","title":"dup"}},{"web":{"uri":"javascript:1","title":"bad"}}]}}]}"""
        val r = WebSearchWire.parse(j)!!
        assertEquals("Result text", r.summary)
        assertEquals(listOf(WebSearchWire.Source("A", "https://a.com/x")), r.sources)
    }
    @Test fun noSourcesIsKeptVisibleAndEmptyIsNull() {
        assertTrue(WebSearchWire.parse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""")!!.sources.isEmpty())
        assertNull(WebSearchWire.parse("""{"candidates":[]}"""))
        assertNull(WebSearchWire.parse("not json"))
    }
}
