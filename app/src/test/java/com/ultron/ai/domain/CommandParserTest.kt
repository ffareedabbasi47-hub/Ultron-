package com.ultron.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertTrue

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

class ToggleAndYouTubeTest {
    private fun t(s: String) = CommandParser.parse(s)

    @org.junit.Test fun toggles() {
        assertEquals(UltronAction.SetToggle(ToggleTarget.WIFI, true), t("wifi on karo"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.WIFI, false), t("Wi-Fi band kar do"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.HOTSPOT, true), t("hotspot chalu kar"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.HOTSPOT, false), t("turn off hotspot"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.BLUETOOTH, true), t("turn on bluetooth"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.BLUETOOTH, false), t("bluetooth off"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.AIRPLANE, true), t("airplane mode on"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.TORCH, true), t("torch chalu karo"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.MOBILE_DATA, false), t("mobile data band karo"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.WIFI, true), t("Hey ULTRON, wifi on karo please"))
    }

    @org.junit.Test fun settingsScreensStillWork() {
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.WIFI), t("wifi settings"))
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.HOTSPOT), t("hotspot settings kholo"))
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.BATTERY), t("battery settings"))
        assertEquals(UltronAction.OpenApp("whatsapp"), t("whatsapp kholo"))
    }

    @org.junit.Test fun youtubeFirstVideo() {
        val html = """x{"adSlotRenderer":{"videoId":"AAAAAAAAAAA"}}..."videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail":{}}"videoRenderer":{"videoId":"bbbbbbbbbbb"}"""
        assertEquals("dQw4w9WgXcQ", YouTubeResults.firstVideoId(html))
        assertEquals("zzzzzzzzzzz", YouTubeResults.firstVideoId("{\"videoId\":\"zzzzzzzzzzz\"}"))
        assertEquals(null, YouTubeResults.firstVideoId("<html>consent</html>"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeResults.watchUrl("dQw4w9WgXcQ"))
    }

    @org.junit.Test fun hints() {
        assertTrue(ToggleHints.hints(ToggleTarget.HOTSPOT).none { it.contains("tethering") })
        assertTrue(ToggleHints.hints(ToggleTarget.TORCH).isEmpty())
    }
}

class DeviceCommandTest {
    private fun t(s: String) = CommandParser.parse(s)
    @org.junit.Test fun volume() {
        assertEquals(UltronAction.AdjustVolume(true), t("volume badhao"))
        assertEquals(UltronAction.AdjustVolume(false), t("volume kam karo"))
        assertEquals(UltronAction.AdjustVolume(false), t("volume ghatao"))
        assertEquals(UltronAction.AdjustVolume(true), t("increase the volume"))
        assertEquals(UltronAction.OpenSystemSettings(SettingsTarget.SOUND), t("volume settings"))
    }
    @org.junit.Test fun brightness() {
        assertEquals(UltronAction.SetBrightness(50), t("brightness 50 percent kar do"))
        assertEquals(UltronAction.SetBrightness(null, 20), t("brightness badhao"))
        assertEquals(UltronAction.SetBrightness(null, -20), t("brightness kam karo"))
        assertEquals(UltronAction.SetBrightness(100), t("brightness full"))
        assertEquals(UltronAction.SetBrightness(1), t("brightness min"))
    }
    @org.junit.Test fun dnd() {
        assertEquals(UltronAction.SetDnd(true), t("dnd on karo"))
        assertEquals(UltronAction.SetDnd(false), t("do not disturb band karo"))
    }
    @org.junit.Test fun media() {
        assertEquals(UltronAction.MediaControl(MediaCmd.PAUSE), t("video pause karo"))
        assertEquals(UltronAction.MediaControl(MediaCmd.PLAY), t("video resume karo"))
        assertEquals(UltronAction.MediaControl(MediaCmd.NEXT), t("next video"))
        assertEquals(UltronAction.MediaControl(MediaCmd.PREVIOUS), t("previous video"))
        assertEquals(UltronAction.MediaControl(MediaCmd.CLOSE_YOUTUBE), t("youtube band karo"))
        assertEquals(UltronAction.StopSpeaking, t("stop"))
    }
    @org.junit.Test fun invalidBrightnessRejected() {
        assertTrue(!ActionValidator.validate(UltronAction.SetBrightness(150)).ok)
        assertTrue(ActionValidator.validate(UltronAction.SetBrightness(40)).ok)
    }
    @org.junit.Test fun oldBehaviourKept() {
        assertEquals(UltronAction.YouTubeSearch("despacito"), t("play despacito"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.WIFI, true), t("wifi on karo"))
        assertEquals(UltronAction.OpenApp("whatsapp"), t("whatsapp kholo"))
    }
}
