package com.ultron.ai.domain

import org.junit.Assert.*
import org.junit.Test

class EnglishCommandTest {
    private fun p(s: String) = CommandParser.parse(s)
    private val papa = listOf(ContactEntry("1", "Papa", "+919876543210", true))

    @Test fun callMyFatherResolvesPapa() {
        assertEquals(UltronAction.CallContact("my father"), p("Call my father"))
        assertEquals(ContactMatch.One("Papa", "+919876543210"), ContactMatcher.find("my father", papa))
    }
    @Test fun relationAliasesNeverPickBetweenPeople() {
        val two = papa + ContactEntry("2", "Dad Office", "+911111111111", true)
        assertTrue(ContactMatcher.find("my father", two) is ContactMatch.One) // literal 'papa' group found first; no second person guessed
        val twoPapas = listOf(ContactEntry("3", "Papa Ji", "+912222222222", true), ContactEntry("4", "Papa Office", "+913333333333", true))
        assertTrue(ContactMatcher.find("papa", twoPapas) is ContactMatch.Ambiguous)
    }
    @Test fun whatsappEnglishWithColon() {
        assertEquals(UltronAction.SendMessage("my father", "I am coming home", true), p("Send WhatsApp message to my father: I am coming home"))
    }
    @Test fun whatsappHinglishWithColon() {
        assertEquals(UltronAction.SendMessage("father", "Main ghar aa raha hoon", true), p("WhatsApp par father ko message bhejo: Main ghar aa raha hoon"))
    }
    @Test fun whatsappDirect() {
        assertEquals(UltronAction.SendMessage("papa", "ghar aa jao", true), p("whatsapp papa: ghar aa jao"))
    }
    @Test fun smsStaysSeparate() {
        assertEquals(UltronAction.SendMessage("papa", "hello", false), p("send sms to papa saying hello"))
    }
    @Test fun noBodyStillPrepares() {
        assertEquals(UltronAction.SendMessage("papa", null, true), p("send whatsapp message to papa"))
    }
    @Test fun youtubeVideoAbout() {
        assertEquals(UltronAction.YouTubeSearch("transformer safety"), p("Play a video about transformer safety"))
    }
}
