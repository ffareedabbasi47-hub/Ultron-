package com.ultron.ai.domain

import org.junit.Assert.*
import org.junit.Test

class WhatsAppGuardTest {
    private val body = "Main ghar aa raha hoon.\nChai bana do, aur dawai bhi le aana. शुक्रिया!"
    private fun snap(pkg: String = "com.whatsapp", edit: String = body, header: String = "Papa", send: String? = "Send") = ScreenSnapshot(pkg, buildList {
        add(ScreenNode(0, header, false, false, false)); add(ScreenNode(1, edit.replace('\n', ' '), false, true, false))
        if (send != null) add(ScreenNode(2, send, true, false, false))
    })

    @Test fun readyOnlyWhenAppChatAndExactTextMatch() =
        assertEquals(WhatsAppGuard.Result.Ready(2), WhatsAppGuard.check(snap(), "Papa", "+919876543210", body))
    @Test fun wrongChatIsBlocked() = assertTrue(WhatsAppGuard.check(snap(header = "Mummy"), "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
    @Test fun numberOnScreenCountsAsRecipient() =
        assertTrue(WhatsAppGuard.check(snap(header = "+91 98765 43210"), "Papa", "+919876543210", body) is WhatsAppGuard.Result.Ready)
    @Test fun truncatedOrEmptyMessageBoxIsBlocked() {
        assertTrue(WhatsAppGuard.check(snap(edit = body.take(40)), "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
        assertTrue(WhatsAppGuard.check(snap(edit = "Message"), "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
    }
    @Test fun otherAppIsBlocked() = assertTrue(WhatsAppGuard.check(snap(pkg = "com.android.chrome"), "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
    @Test fun missingSendButtonIsBlocked() = assertTrue(WhatsAppGuard.check(snap(send = null), "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
    @Test fun noSnapshotIsBlocked() = assertTrue(WhatsAppGuard.check(null, "Papa", "+919876543210", body) is WhatsAppGuard.Result.NotReady)
    @Test fun leftBoxDetection() {
        assertFalse(WhatsAppGuard.messageLeftBox(snap(), body))
        assertTrue(WhatsAppGuard.messageLeftBox(snap(edit = "Message"), body))
    }
    @Test fun longMessageKeptWhole() {
        val long = "Namaste. ".repeat(300) // 2700 chars
        val a = CommandParser.parse("send whatsapp message to papa: $long")
        assertEquals(long.trim(), (a as UltronAction.SendMessage).body)
        assertEquals(Validation.OK, ActionValidator.validate(a))
        assertNotEquals(Validation.OK, ActionValidator.validate(UltronAction.SendMessage("papa", "x".repeat(4001), true)))
    }
    @Test fun parserKeepsCaseAndPunctuation() {
        val a = CommandParser.parse("WhatsApp par Papa ko message bhejo: Main ghar aa raha hoon, Chai bana do!") as UltronAction.SendMessage
        assertEquals("Main ghar aa raha hoon, Chai bana do!", a.body)
    }
}
