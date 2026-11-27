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

class CallTest {
    private fun t(s: String) = CommandParser.parse(s)
    @org.junit.Test fun parse() {
        assertEquals(UltronAction.CallContact("papa"), t("Papa ko call karo"))
        assertEquals(UltronAction.CallContact("mom"), t("mom ko call karo"))
        assertEquals(UltronAction.CallContact("rahul"), t("call rahul"))
        assertEquals(UltronAction.CallContact("papa"), t("call karo papa ko"))
        assertEquals(UltronAction.CallNumber("9876543210"), t("is number par call karo 98765 43210"))
        assertEquals(UltronAction.CallNumber("+919876543210"), t("+91 98765 43210 par call karo"))
        assertEquals(UltronAction.OpenDialer, t("dialer kholo"))
        assertEquals(UltronAction.EndCall, t("call cancel karo"))
        assertEquals(null, t("call"))
    }
    @org.junit.Test fun matcher() {
        val e = listOf(
            ContactEntry("1", "Papa", "98765 43210", true),
            ContactEntry("2", "Rahul Sharma", "90000 11111", true), ContactEntry("3", "Rahul Verma", "92222 33333", true),
            ContactEntry("4", "Mom", "93333 44444", false), ContactEntry("4", "Mom", "94444 55555", false),
            ContactEntry("5", "Amit", "95555 66666", true), ContactEntry("5", "Amit", "+91 95555 66666", false),
        )
        assertEquals(ContactMatch.One("Papa", "9876543210"), ContactMatcher.find("papa", e))
        assertEquals(ContactMatch.None, ContactMatcher.find("sunil", e))
        assertTrue(ContactMatcher.find("rahul", e) is ContactMatch.Ambiguous)
        assertEquals(ContactMatch.One("Rahul Verma", "9222233333"), ContactMatcher.find("rahul verma", e))
        assertTrue(ContactMatcher.find("mom", e) is ContactMatch.MultiNumber)
        assertTrue(ContactMatcher.find("amit", e) is ContactMatch.One) // same number twice = one number
        assertEquals(null, ContactMatcher.normalizeNumber("12"))
        assertEquals("+919876543210", ContactMatcher.normalizeNumber("+91 98765-43210"))
    }
}

class YouTubePickTest {
    private fun item(id: String, title: String, ch: String, len: String, age: String, views: String) =
        "\"videoRenderer\":{\"videoId\":\"$id\",\"title\":{\"runs\":[{\"text\":\"$title\"}]},\"ownerText\":{\"runs\":[{\"text\":\"$ch\"}]},\"publishedTimeText\":{\"simpleText\":\"$age\"},\"viewCountText\":{\"simpleText\":\"$views\"},\"lengthText\":{\"accessibility\":{\"accessibilityData\":{\"label\":\"x\"}},\"simpleText\":\"$len\"}}"

    @org.junit.Test fun parsesCandidates() {
        val html = item("AAAAAAAAAAA", "Random cats", "CatTV", "3:10", "2 years ago", "5M views") + item("BBBBBBBBBBB", "Iron Man new technology \\\"suit\\\" explained", "Tech Daily", "12:34", "2 days ago", "1.2M views")
        val c = YouTubeResults.candidates(html)
        assertEquals(2, c.size)
        assertEquals("Iron Man new technology \"suit\" explained", c[1].title)
        assertEquals("Tech Daily", c[1].channel); assertEquals(754, c[1].durationSec); assertEquals(2880L, c[1].ageMinutes); assertEquals(1_200_000L, c[1].views)
    }
    @org.junit.Test fun notBlindlyFirst() {
        val html = item("AAAAAAAAAAA", "Random cats", "CatTV", "3:10", "2 years ago", "50M views") +
            item("CCCCCCCCCCC", "Iron Man reaction short", "X", "0:30", "1 day ago", "10 views") +
            item("BBBBBBBBBBB", "Iron Man technology latest explained", "Tech Daily", "12:34", "2 days ago", "1.2M views")
        val p = YouTubeRanker.pick("iron man latest technology", YouTubeResults.candidates(html))!!
        assertEquals("BBBBBBBBBBB", p.candidate.id)
    }
    @org.junit.Test fun latestPrefersRecent() {
        val html = item("AAAAAAAAAAA", "Space exploration documentary", "NASA", "40:00", "3 years ago", "9M views") + item("BBBBBBBBBBB", "Space exploration news this week", "NASA", "8:00", "1 day ago", "100K views")
        assertEquals("BBBBBBBBBBB", YouTubeRanker.pick("latest space exploration", YouTubeResults.candidates(html))!!.candidate.id)
        assertEquals("AAAAAAAAAAA", YouTubeRanker.pick("space exploration documentary", YouTubeResults.candidates(html))!!.candidate.id)
    }
    @org.junit.Test fun emptyOrMissingDataInventsNothing() {
        assertEquals(null, YouTubeRanker.pick("x", emptyList()))
        assertTrue(YouTubeResults.candidates("<html>no results</html>").isEmpty())
    }
    @org.junit.Test fun queryAndParse() {
        assertEquals("iron man latest technology", YouTubeQuery.clean("mujhe Iron Man ki latest technology video"))
        assertEquals(UltronAction.YouTubeSearch("iron man ki latest technology"), CommandParser.parse("mujhe Iron Man ki latest technology video chalao"))
        assertEquals(UltronAction.YouTubeSearch("space exploration"), CommandParser.parse("space exploration video chalao"))
        assertEquals(UltronAction.SetToggle(ToggleTarget.WIFI, true), CommandParser.parse("wifi on karo"))
    }
}

class AlarmMessageTest {
    private fun t(s: String) = CommandParser.parse(s)
    @org.junit.Test fun alarms() {
        assertEquals(UltronAction.SetAlarm(6, 0, false, null, emptyList(), false), t("subah 6 baje alarm laga do"))
        assertEquals(UltronAction.SetAlarm(0, 0, false, 10, emptyList(), false), t("10 minute baad alarm lagao"))
        assertEquals(UltronAction.SetAlarm(7, 30, true, null, emptyList(), true), t("kal 7:30 baje alarm lagana"))
        assertEquals(UltronAction.SetAlarm(17, 5, false, null, emptyList(), false), t("shaam 5:05 baje alarm lagao"))
        assertEquals(UltronAction.SetAlarm(18, 0, false, null, emptyList(), false), t("alarm 18:00 baje set karo"))
        assertEquals(UltronAction.SetAlarm(6, 30, false, null, (1..7).toList(), false), t("roz subah 6:30 baje alarm lagao"))
        assertEquals(UltronAction.SetAlarm(2, 0, false, null, emptyList(), false), t("raat 2 baje alarm lagao"))
        assertEquals(UltronAction.SetTimer(300), t("5 minute ka timer lagao"))
        assertEquals(UltronAction.DismissAlarm, t("mera alarm band karo"))
        assertEquals(UltronAction.DismissAlarm, t("alarm delete karo"))
        assertEquals(UltronAction.ShowAlarms, t("saare alarms dikhao"))
        assertEquals(UltronAction.ShowAlarms, t("kal ke alarms batao"))
    }
    @org.junit.Test fun messages() {
        assertEquals(UltronAction.SendMessage("rahul", "main ghar aa raha hoon", false), t("Rahul ko message bhejo ki main ghar aa raha hoon"))
        assertEquals(UltronAction.SendMessage("mom", null, false), t("mom ko sms bhejo"))
        assertEquals(UltronAction.SendMessage("papa", "kal milte hain", true), t("whatsapp par papa ko message bhejo ki kal milte hain"))
        assertEquals(UltronAction.SendMessage("rahul", "late ho jaunga", false), t("send message to rahul saying late ho jaunga"))
        assertEquals(UltronAction.CallContact("papa"), t("papa ko call karo"))
    }
    @org.junit.Test fun memoryPhrase() {
        assertEquals(UltronAction.Remember("mujhe black aur gold theme pasand hai"), t("yaad rakhna ki mujhe black aur gold theme pasand hai"))
    }
    @org.junit.Test fun callSilence() {
        val c = com.ultron.ai.domain.live.CallSilence()
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.PAUSE, c.onCallState(true, true))
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.NONE, c.onCallState(true, false))
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.RESUME, c.onCallState(false, false))
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.NONE, c.onCallState(false, false))
        // was NOT listening before the call -> nothing to resume
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.NONE, c.onCallState(true, false))
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.NONE, c.onCallState(false, false))
        // user stops ULTRON during a call -> no auto resume
        c.onCallState(true, true); c.userStopped()
        assertEquals(com.ultron.ai.domain.live.CallSilenceAction.NONE, c.onCallState(false, false))
    }
}
