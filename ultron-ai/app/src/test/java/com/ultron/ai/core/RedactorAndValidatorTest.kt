package com.ultron.ai.core

import com.ultron.ai.core.diagnostics.Redactor
import com.ultron.ai.data.ai.StreamProtocol
import com.ultron.ai.domain.ActionValidator
import com.ultron.ai.domain.UltronAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorAndValidatorTest {

    @Test fun `redacts keys bearer tokens emails and phones`() {
        val s = "key=AIzaSyA1234567890abcdefghijklmnop Authorization: Bearer abcdef1234567890xyz " +
            "mail me a.b@example.com call +91 98765 43210"
        val out = Redactor.redact(s)
        assertFalse(out.contains("AIza"))
        assertFalse(out.contains("abcdef1234567890xyz"))
        assertFalse(out.contains("a.b@example.com"))
        assertFalse(out.contains("98765"))
    }

    @Test fun `validator rejects bad reminder and app names`() {
        assertFalse(ActionValidator.validate(UltronAction.SetReminder("x", 0)).ok)
        assertFalse(ActionValidator.validate(UltronAction.SetReminder("x", 999_999)).ok)
        assertTrue(ActionValidator.validate(UltronAction.SetReminder("chai", 10)).ok)
        assertFalse(ActionValidator.validate(UltronAction.OpenApp("a;rm -rf /")).ok)
        assertTrue(ActionValidator.validate(UltronAction.OpenApp("WhatsApp")).ok)
        assertFalse(ActionValidator.validate(UltronAction.YouTubeSearch("")).ok)
    }

    @Test fun `sse line parsing`() {
        val e = StreamProtocol.parseLine("""data: {"delta":"Namaste"}""")
        assertNotNull(e)
        assertEquals("Namaste", e!!.delta)
        assertEquals(true, StreamProtocol.parseLine("""data: {"done":true,"usage":{"totalTokens":12}}""")!!.done)
        assertNull(StreamProtocol.parseLine(": keep-alive"))
        assertNull(StreamProtocol.parseLine("data: not-json"))
    }
}
