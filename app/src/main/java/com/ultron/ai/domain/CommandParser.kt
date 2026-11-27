package com.ultron.ai.domain

/**
 * Local, offline fast-path for simple Roman-Hinglish / English commands.
 * Returns null when the text is not a command, so it falls through to the AI provider.
 */
object CommandParser {

    private const val UNIT = "(min(?:ute|utes|s)?|minit|ghanta|ghante|hours?|hrs?)"

    private val wakePrefix = Regex("^(?:(?:hey|hi|hello|ok)\\s+)?ultron[,.!]?\\s+")
    private val trailingPunct = Regex("[.!?।\\s]+$")
    private val politeness = Regex("\\b(please|plz|pls|zara|jara)\\b")
    private val spaces = Regex("\\s+")

    private val stop = Regex("^(chup|chup ho ja|chup ho jao|stop|ruk ja|ruko|bas|stop speaking|shant)$")

    private val remember = Regex("^(?:yaad rakh(?:na|o)?|remember(?: that)?|note kar(?:o)?)\\s*[:\\-]?\\s+(?:ki\\s+)?(.+)$")

    private val remindEn = Regex("^remind(?:er)?\\s+(?:me\\s+)?(?:to\\s+)?(.+?)\\s+in\\s+(\\d{1,4})\\s*$UNIT$")
    private val remindHi = Regex(
        "^(\\d{1,4})\\s*$UNIT\\s*(?:mein|me|baad|ke baad|later)\\s+(.+?)\\s+(?:yaad\\s*dila\\w*|remind\\w*(?:\\s+kar\\w*)?)$",
    )

    private val settingsSpecific = Regex(
        "^(?:open\\s+)?(wifi|wi-fi|bluetooth|display|sound|volume|notification|notifications|hotspot|hot spot|airplane|airplane mode|flight mode|mobile data|data|location|gps|battery|apps?|storage|nfc|date|time|language|accessibility|security|network|internet|dnd|do not disturb)\\s+settings?(?:\\s+(?:khol|kholo|kholna|open))?$",
    )

    // "wifi on karo", "hotspot band kar do", "turn off bluetooth", "torch chalu"
    private const val TOG_NAME = "(wi-?fi|hot ?spot|bluetooth|airplane mode|flight mode|airplane|mobile data|data|location|gps|nfc|torch|flashlight|flash light)"
    private const val ON_W = "(on|off|chalu|shuru|start|enable|band|bandh|stop|disable)"
    private const val DO_W = "(?:\\s+(?:kar|karo|kardo|kar do|kr do|krdo|kar de|karna|do))?"
    private val toggleVerbFirst = Regex("^(?:turn|switch|set)\\s+(on|off)\\s+(?:the\\s+)?$TOG_NAME$")
    private val toggleNameMid = Regex("^(?:turn|switch)\\s+(?:the\\s+)?$TOG_NAME\\s+(on|off)$")
    private val toggleNameFirst = Regex("^$TOG_NAME\\s+(?:ko\\s+)?$ON_W$DO_W$")
    private val settingsMain = Regex("^(?:open\\s+)?settings?(?:\\s+(?:khol|kholo|kholna|open|open kar|open karo))?$")

    // Explicit hand-offs to the Builder agent: "builder se X banwao" / "X website banao".
    private val builderCmd = Regex("^builder(?: agent)?\\s*(?:se|ko|:)?\\s*(.{6,}?)(?:\\s+(?:banwao|banao|bana do|bana de|bana|build karo|build))?$")
    private val builderSite = Regex("^(.{4,}?)\\s+(website|app|webpage|landing page|web app)\\s+(?:banwao|banao|bana do|bana de)$")
    private val youtubeVerbs = "(?:chalao|chala|play|search|dikhao|dikha|laga|lagao|dhundo|dhoondo|khol|kholo|open)"
    private val youtubeFirst = Regex("^(?:youtube|yt)\\s+(?:pe|par|per|on)\\s+(.+?)(?:\\s+$youtubeVerbs)?$")
    private val youtubeLast = Regex("^(.+?)\\s+(?:youtube|yt)\\s+(?:pe|par|per|me|mein|on)(?:\\s+$youtubeVerbs)?$")
    private val ytPlayTopic = Regex("^(?:mujhe\\s+)?(.+?)\\s+(?:video\\s+)?(?:chalao|chala do|chala de|play karo|play kar do|laga do|lagao)$")
    private val playQuery = Regex("^(?:play|chalao|chala do|gaana|gana)\\s+(.+)$")

    private val openEn = Regex("^(?:open|launch|start)\\s+(.+)$")
    private val openHi = Regex("^(.+?)\\s+(?:khol|kholo|kholna|open kar|open karo|chalu kar|chalu karo|start kar|start karo)$")

    private val navBack = Regex("^(?:back|peeche|piche|wapas|vapas)(?:\\s+(?:jao|ja|chalo|karo|kar do))?$")
    private val navHome = Regex("^(?:home|home screen)(?:\\s+(?:jao|ja|chalo|par jao|pe jao|karo))?$")
    private val navRecents = Regex("^(?:recent apps?|recents|recent screen)(?:\\s+(?:dikhao|kholo|khol|show))?$")
    private val navNotif = Regex("^(?:notifications?|notification panel|notification bar)(?:\\s+(?:dikhao|kholo|khol|show|neeche karo))?$")
    private val scrollDown = Regex("^(?:scroll\\s+down|neeche\\s+scroll(?:\\s+kar\\w*)?|scroll\\s+neeche|niche\\s+scroll(?:\\s+kar\\w*)?|scroll\\s+karo|neeche jao|niche jao)$")
    private val scrollUp = Regex("^(?:scroll\\s+up|upar\\s+scroll(?:\\s+kar\\w*)?|scroll\\s+upar|upar jao)$")
    private val readScreen = Regex("^(?:screen\\s+(?:par|pe|me|mein)\\s+kya\\s+(?:hai|likha hai)|screen\\s+padh(?:o|na)?|what(?:'s| is)\\s+on\\s+(?:my\\s+)?screen|read\\s+(?:my\\s+)?screen)$")
    private val clickEn = Regex("^(?:click|tap|press)\\s+(?:on\\s+)?(.+)$")
    private val clickHi = Regex("^(.+?)\\s+(?:pe|par|per)\\s+(?:click|tap|press)(?:\\s+kar\\w*)?$|^(.+?)\\s+(?:dabao|dabana|dabaiye|dabado|dba do)$")
    private val typeEn = Regex("^(?:type|likho|likh do|likh)\\s*[:\\-]?\\s+(.+)$")
    private val task = Regex("^(?:agent|task|kaam)\\s*[:\\-]?\\s+(.+)$|^(?:phone|screen)\\s+(?:mein|me|pe|par)\\s+(.+?)\\s+(?:kar do|karo|kar|kardo|karna)$")

    fun parse(raw: String): UltronAction? {
        val t = normalize(raw)
        if (t.isEmpty() || t.length > 200) return null

        if (stop.matches(t)) return UltronAction.StopSpeaking

        if (navBack.matches(t)) return UltronAction.Navigate(NavKind.BACK)
        if (navHome.matches(t)) return UltronAction.Navigate(NavKind.HOME)
        if (navRecents.matches(t)) return UltronAction.Navigate(NavKind.RECENTS)
        if (navNotif.matches(t)) return UltronAction.Navigate(NavKind.NOTIFICATIONS)
        if (scrollDown.matches(t)) return UltronAction.Scroll(down = true)
        if (scrollUp.matches(t)) return UltronAction.Scroll(down = false)
        if (readScreen.matches(t)) return UltronAction.ReadScreen
        task.matchEntire(t)?.let { m -> return UltronAction.ScreenTask((m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()) }
        typeEn.matchEntire(t)?.let { return UltronAction.TypeText(raw.trim().replace(Regex("^(?i)(?:type|likho|likh do|likh)\\s*[:\\-]?\\s+"), "").trim().ifEmpty { it.groupValues[1] }) }
        clickEn.matchEntire(t)?.let { return UltronAction.Click(it.groupValues[1].trim()) }
        clickHi.matchEntire(t)?.let { m -> return UltronAction.Click((m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()) }

        parseAlarm(t)?.let { return it }
        parseMessage(t)?.let { return it }
        parseCall(t)?.let { return it }
        parseToggle(t)?.let { return it }
        parseDevice(t)?.let { return it }

        remember.matchEntire(t)?.let { return UltronAction.Remember(it.groupValues[1].trim()) }

        remindEn.matchEntire(t)?.let {
            return UltronAction.SetReminder(it.groupValues[1].trim(), toMinutes(it.groupValues[2], it.groupValues[3]))
        }
        remindHi.matchEntire(t)?.let {
            return UltronAction.SetReminder(it.groupValues[3].trim(), toMinutes(it.groupValues[1], it.groupValues[2]))
        }

        settingsSpecific.matchEntire(t)?.let { m ->
            val target = when (m.groupValues[1]) {
                "wifi", "wi-fi" -> SettingsTarget.WIFI
                "bluetooth" -> SettingsTarget.BLUETOOTH
                "display" -> SettingsTarget.DISPLAY
                "sound", "volume" -> SettingsTarget.SOUND
                "hotspot", "hot spot" -> SettingsTarget.HOTSPOT
                "airplane", "airplane mode", "flight mode" -> SettingsTarget.AIRPLANE
                "mobile data", "data" -> SettingsTarget.MOBILE_DATA
                "location", "gps" -> SettingsTarget.LOCATION
                "battery" -> SettingsTarget.BATTERY
                "app", "apps" -> SettingsTarget.APPS
                "storage" -> SettingsTarget.STORAGE
                "nfc" -> SettingsTarget.NFC
                "date", "time" -> SettingsTarget.DATE
                "language" -> SettingsTarget.LANGUAGE
                "accessibility" -> SettingsTarget.ACCESSIBILITY
                "security" -> SettingsTarget.SECURITY
                "network", "internet" -> SettingsTarget.NETWORK
                "dnd", "do not disturb" -> SettingsTarget.DND
                else -> SettingsTarget.APP_NOTIFICATIONS
            }
            return UltronAction.OpenSystemSettings(target)
        }
        if (settingsMain.matches(t)) return UltronAction.OpenSystemSettings(SettingsTarget.MAIN)

        youtubeFirst.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }
        youtubeLast.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }
        playQuery.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }

        ytPlayTopic.matchEntire(t)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty() && q !in setOf("wifi", "wi-fi", "hotspot", "bluetooth", "torch", "flashlight", "data", "location")) return UltronAction.YouTubeSearch(q)
        }

        builderCmd.matchEntire(t)?.let { return UltronAction.BuildProject(it.groupValues[1].trim()) }
        builderSite.matchEntire(t)?.let { return UltronAction.BuildProject(it.groupValues[1].trim() + " " + it.groupValues[2]) }

        openEn.matchEntire(t)?.let { return UltronAction.OpenApp(it.groupValues[1].trim()) }
        openHi.matchEntire(t)?.let { return UltronAction.OpenApp(it.groupValues[1].trim()) }

        return null
    }

    private fun toggleTarget(n: String): ToggleTarget = when (n.replace(" ", "")) {
        "wifi", "wi-fi" -> ToggleTarget.WIFI
        "hotspot" -> ToggleTarget.HOTSPOT
        "bluetooth" -> ToggleTarget.BLUETOOTH
        "airplanemode", "flightmode", "airplane" -> ToggleTarget.AIRPLANE
        "mobiledata", "data" -> ToggleTarget.MOBILE_DATA
        "location", "gps" -> ToggleTarget.LOCATION
        "nfc" -> ToggleTarget.NFC
        else -> ToggleTarget.TORCH
    }

    private fun isOn(w: String) = w == "on" || w == "chalu" || w == "shuru" || w == "start" || w == "enable"

    internal fun parseToggle(t: String): UltronAction? {
        toggleVerbFirst.matchEntire(t)?.let { return UltronAction.SetToggle(toggleTarget(it.groupValues[2]), isOn(it.groupValues[1])) }
        toggleNameMid.matchEntire(t)?.let { return UltronAction.SetToggle(toggleTarget(it.groupValues[1]), isOn(it.groupValues[2])) }
        toggleNameFirst.matchEntire(t)?.let { return UltronAction.SetToggle(toggleTarget(it.groupValues[1]), isOn(it.groupValues[2])) }
        return null
    }

    private const val DO2 = "(?:\\s+(?:kar|karo|kardo|kar do|kr do|do|de))?"
    private const val UPW = "(badha\\w*|increase|raise|up|zyada|tez|high|bright|kam|ghata\\w*|decrease|lower|down|low|dheere|dhimi|dim)"
    private val volWord = Regex("^(?:volume|awaz|awaaz|aawaz|sound)\\s+(?:thoda\\s+|aur\\s+)?$UPW$DO2$")
    private val volEn = Regex("^(increase|decrease|raise|lower|turn up|turn down)\\s+(?:the\\s+)?volume$")
    private val briPct = Regex("^(?:set\\s+)?brightness\\s+(?:ko\\s+|to\\s+)?(\\d{1,3})\\s*(?:%|percent|pratishat)?$DO2$")
    private val briWord = Regex("^brightness\\s+(?:thoda\\s+|aur\\s+)?$UPW$DO2$")
    private val briMaxMin = Regex("^brightness\\s+(full|max|maximum|min|minimum)$DO2$")
    private val dnd = Regex("^(?:dnd|do not disturb|disturb mat karo)\\s*(?:mode\\s+)?(on|off|chalu|band|enable|disable|start|stop)$DO2$")
    private val mPause = Regex("^(?:(?:video|song|gaana|music)\\s+)?pause(?:\\s+(?:video|song|gaana|music))?$DO2$")
    private val mResume = Regex("^(?:(?:video|song|gaana|music)\\s+)?(?:resume|continue)(?:\\s+(?:video|song|gaana|music))?$DO2$|^(?:video|song|gaana|music)\\s+(?:chalu|play)$DO2$")
    private val mNext = Regex("^(?:next|agla|agli)(?:\\s+(?:video|song|gaana|track))?(?:\\s+(?:chalao|karo|laga do|lagao))?$")
    private val mPrev = Regex("^(?:previous|prev|pichla|pichli|peechla|peechli)(?:\\s+(?:video|song|gaana|track))?(?:\\s+(?:chalao|karo|laga do|lagao))?$")
    private val ytClose = Regex("^(?:youtube|yt)\\s+(?:band|close|stop)$DO2$|^(?:close|band kar|band karo)\\s+(?:youtube|yt)$")

    private val msgKo = Regex("^(?:whatsapp\\s+(?:par|pe)\\s+)?(.+?)\\s+ko\\s+(?:whatsapp\\s+(?:par\\s+)?)?(?:message|sms|msg|text)\\s+(?:bhejo|bhej do|bhejna|karo|kar do|send karo|likho|draft karo)(?:\\s+(?:ki|ke)\\s+(.+))?$")
    private val msgEn = Regex("^(?:send|text)\\s+(?:a\\s+)?(?:message|sms|text\\s+)?\\s*to\\s+(.+?)(?:\\s+(?:saying|that|ki)\\s+(.+))?$")

    internal fun parseMessage(t: String): UltronAction? {
        val m = msgKo.matchEntire(t) ?: msgEn.matchEntire(t) ?: return null
        val name = m.groupValues[1].trim(); if (name.isEmpty()) return null
        return UltronAction.SendMessage(name, m.groupValues[2].trim().ifEmpty { null }, t.contains("whatsapp"))
    }

    private val alarmSetVerb = Regex("\\b(lagao|laga do|lagana|laga de|lagado|set|kar do|karo|bana do|rakh do|chahiye|laga dena)\\b")
    private val alarmOffVerb = Regex("\\b(band|delete|cancel|hatao|hata do|dismiss|remove|off)\\b")
    private val alarmShow = Regex("\\b(dikhao|batao|show|list|kitne|kaun se|dikha)\\b")

    internal fun parseAlarm(t: String): UltronAction? {
        if (t.contains("timer")) {
            if (alarmOffVerb.containsMatchIn(t)) return null
            return AlarmTime.timerSeconds(t)?.let { UltronAction.SetTimer(it) }
        }
        if (!Regex("\\balarms?\\b").containsMatchIn(t)) return null
        if (alarmShow.containsMatchIn(t)) return UltronAction.ShowAlarms
        if (alarmOffVerb.containsMatchIn(t)) return UltronAction.DismissAlarm
        if (!alarmSetVerb.containsMatchIn(t)) return null
        val p = AlarmTime.parse(t) ?: return null
        return UltronAction.SetAlarm(p.hour, p.minute, p.ambiguous, p.inMinutes, p.days, p.tomorrow)
    }

    private const val NUM = "([+\\d][\\d\\s-]{4,18})"
    private val callEnd = Regex("^(?:call\\s+(?:cancel|end|kaat|kat|band)(?:\\s+(?:karo|kar do|do))?|(?:call|phone)\\s+(?:cut|kaat)\\s*(?:karo|kar do|do)?|hang up|call kaat do)$")
    private val dialer = Regex("^(?:dialer|phone app|dial pad|dialpad)(?:\\s+(?:kholo|khol|open|open karo|dikhao))?$|^open\\s+(?:the\\s+)?dialer$")
    private val callNumA = Regex("^(?:(?:is\\s+)?number\\s+(?:par|pe)\\s+)?call\\s*(?:kar|karo|kardo|kar do)?\\s*$NUM$")
    private val callNumB = Regex("^$NUM\\s+(?:par|pe|ko)\\s+(?:call|phone)\\s*(?:kar|karo|kardo|kar do)?$")
    private val callKarFirst = Regex("^(?:call|phone)\\s+(?:kar|karo|kardo|kar do)\\s+(.+?)(?:\\s+ko)?$")
    private val callKo = Regex("^(.+?)\\s+ko\\s+(?:phone|call|fone)\\s*(?:kar|karo|kardo|kar do|laga|lagao|laga do|lagana)?$")
    private val callEn = Regex("^(?:call|phone|ring)\\s+(.+)$")

    internal fun parseCall(t: String): UltronAction? {
        if (callEnd.matches(t)) return UltronAction.EndCall
        if (dialer.matches(t)) return UltronAction.OpenDialer
        (callNumA.matchEntire(t) ?: callNumB.matchEntire(t))?.let { m -> ContactMatcher.normalizeNumber(m.groupValues[1])?.let { return UltronAction.CallNumber(it) } }
        val name = (callKarFirst.matchEntire(t) ?: callKo.matchEntire(t) ?: callEn.matchEntire(t))?.groupValues?.get(1)?.trim() ?: return null
        if (name.isEmpty() || name in setOf("karo", "kar", "me", "mujhe")) return null
        return UltronAction.CallContact(name)
    }

    private fun isUp(w: String) = w.startsWith("badha") || w in setOf("increase", "raise", "up", "zyada", "tez", "high", "bright", "turn up")

    internal fun parseDevice(t: String): UltronAction? {
        volWord.matchEntire(t)?.let { return UltronAction.AdjustVolume(isUp(it.groupValues[1])) }
        volEn.matchEntire(t)?.let { return UltronAction.AdjustVolume(isUp(it.groupValues[1])) }
        briPct.matchEntire(t)?.let { val p = it.groupValues[1].toInt(); return UltronAction.SetBrightness(p) }
        briWord.matchEntire(t)?.let { return UltronAction.SetBrightness(null, if (isUp(it.groupValues[1])) 20 else -20) }
        briMaxMin.matchEntire(t)?.let { return UltronAction.SetBrightness(if (it.groupValues[1].startsWith("min")) 1 else 100) }
        dnd.matchEntire(t)?.let { return UltronAction.SetDnd(isOn(it.groupValues[1])) }
        if (ytClose.matches(t)) return UltronAction.MediaControl(MediaCmd.CLOSE_YOUTUBE)
        if (mPause.matches(t)) return UltronAction.MediaControl(MediaCmd.PAUSE)
        if (mResume.matches(t)) return UltronAction.MediaControl(MediaCmd.PLAY)
        if (mNext.matches(t)) return UltronAction.MediaControl(MediaCmd.NEXT)
        if (mPrev.matches(t)) return UltronAction.MediaControl(MediaCmd.PREVIOUS)
        return null
    }

    internal fun normalize(raw: String): String {
        var t = raw.trim().lowercase()
        t = trailingPunct.replace(t, "")
        t = wakePrefix.replace(t, "")
        t = politeness.replace(t, " ")
        t = spaces.replace(t, " ").trim()
        return t
    }

    private fun toMinutes(amount: String, unit: String): Int {
        val n = amount.toIntOrNull() ?: return 0
        return if (unit.startsWith("h") || unit.startsWith("g")) n * 60 else n
    }
}
