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

    private val remember = Regex("^(?:yaad rakh(?:na|o)?|remember(?: that)?|note kar(?:o)?)\\s*[:\\-]?\\s+(.+)$")

    private val remindEn = Regex("^remind(?:er)?\\s+(?:me\\s+)?(?:to\\s+)?(.+?)\\s+in\\s+(\\d{1,4})\\s*$UNIT$")
    private val remindHi = Regex(
        "^(\\d{1,4})\\s*$UNIT\\s*(?:mein|me|baad|ke baad|later)\\s+(.+?)\\s+(?:yaad\\s*dila\\w*|remind\\w*(?:\\s+kar\\w*)?)$",
    )

    private val settingsSpecific = Regex(
        "^(?:open\\s+)?(wifi|wi-fi|bluetooth|display|sound|volume|notification|notifications)\\s+settings?(?:\\s+(?:khol|kholo|kholna|open))?$",
    )
    private val settingsMain = Regex("^(?:open\\s+)?settings?(?:\\s+(?:khol|kholo|kholna|open|open kar|open karo))?$")

    private val youtubeVerbs = "(?:chalao|chala|play|search|dikhao|dikha|laga|lagao|dhundo|dhoondo|khol|kholo|open)"
    private val youtubeFirst = Regex("^(?:youtube|yt)\\s+(?:pe|par|per|on)\\s+(.+?)(?:\\s+$youtubeVerbs)?$")
    private val youtubeLast = Regex("^(.+?)\\s+(?:youtube|yt)\\s+(?:pe|par|per|me|mein|on)(?:\\s+$youtubeVerbs)?$")
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
                else -> SettingsTarget.APP_NOTIFICATIONS
            }
            return UltronAction.OpenSystemSettings(target)
        }
        if (settingsMain.matches(t)) return UltronAction.OpenSystemSettings(SettingsTarget.MAIN)

        youtubeFirst.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }
        youtubeLast.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }
        playQuery.matchEntire(t)?.let { return UltronAction.YouTubeSearch(it.groupValues[1].trim()) }

        openEn.matchEntire(t)?.let { return UltronAction.OpenApp(it.groupValues[1].trim()) }
        openHi.matchEntire(t)?.let { return UltronAction.OpenApp(it.groupValues[1].trim()) }

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
