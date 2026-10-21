package com.ultron.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    @Test fun `youtube search hinglish`() {
        assertEquals(
            UltronAction.YouTubeSearch("arijit singh songs"),
            CommandParser.parse("YouTube pe arijit singh songs chalao"),
        )
    }

    @Test fun `youtube search trailing form`() {
        assertEquals(UltronAction.YouTubeSearch("kgf trailer"), CommandParser.parse("kgf trailer youtube pe dikhao"))
    }

    @Test fun `play query goes to youtube search`() {
        assertEquals(UltronAction.YouTubeSearch("despacito"), CommandParser.parse("play despacito"))
    }

    @Test fun `open app english and hinglish`() {
        assertEquals(UltronAction.OpenApp("whatsapp"), CommandParser.parse("open whatsapp"))
        assertEquals(UltronAction.OpenApp("calculator"), CommandParser.parse("calculator kholo"))
        assertEquals(UltronAction.OpenApp("youtube"), CommandParser.parse("youtube kholo"))
    }

    @Test fun `wake prefix is stripped`() {
        assertEquals(UltronAction.OpenApp("camera"), CommandParser.parse("Hey Ultron, open camera"))
    }

    @Test fun `settings targets`() {
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.WIFI), CommandParser.parse("wifi settings"))
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.MAIN), CommandParser.parse("settings kholo"))
    }

    @Test fun `reminder forms`() {
        assertEquals(
            UltronAction.SetReminder("chai banana", 10),
            CommandParser.parse("10 minute mein chai banana yaad dilana"),
        )
        assertEquals(
            UltronAction.SetReminder("call mom", 120),
            CommandParser.parse("remind me to call mom in 2 hours"),
        )
    }

    @Test fun `remember`() {
        assertEquals(UltronAction.Remember("meri exam date 5 march hai"), CommandParser.parse("yaad rakh: meri exam date 5 March hai"))
    }

    @Test fun `stop`() {
        assertEquals(UltronAction.StopSpeaking, CommandParser.parse("chup"))
    }

    @Test fun `normal questions are not commands`() {
        assertNull(CommandParser.parse("photosynthesis kya hota hai?"))
        assertNull(CommandParser.parse("mujhe ek joke sunao"))
    }
}
