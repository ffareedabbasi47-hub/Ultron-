package com.ultron.ai.domain

/** Natural-language (Hinglish/English) alarm and timer parsing. Pure, unit-tested. Calendar day numbers: SUN=1..SAT=7. */
object AlarmTime {
    data class Parsed(val hour: Int, val minute: Int, val ambiguous: Boolean, val inMinutes: Int?, val days: List<Int>, val tomorrow: Boolean)

    private val dayWords = mapOf(
        "sunday" to 1, "ravivar" to 1, "itwar" to 1, "monday" to 2, "somvar" to 2, "tuesday" to 3, "mangalvar" to 3, "mangal" to 3,
        "wednesday" to 4, "budhvar" to 4, "budh" to 4, "thursday" to 5, "guruvar" to 5, "veervar" to 5, "friday" to 6, "shukravar" to 6,
        "saturday" to 7, "shanivar" to 7, "shani" to 7,
    )
    private val rel = Regex("(\\d+)\\s*(minute|minutes|min|mins|ghante|ghanta|hour|hours|hr|hrs)\\b(?:\\s*(?:baad|bad|me|mein|later))?")
    private val clock = Regex("(\\d{1,2})(?:\\s*[:.]\\s*(\\d{2}))?\\s*(am|pm|baje)?")

    fun parse(raw: String): Parsed? {
        val t = raw.lowercase().replace(Regex("[,]"), " ").replace(Regex("\\s+"), " ").trim()
        val tomorrow = Regex("\\bkal\\b|\\btomorrow\\b").containsMatchIn(t)
        val repeatWord = Regex("\\b(roz|daily|har din|every day|everyday|har|every)\\b").containsMatchIn(t)
        var days = emptyList<Int>()
        if (Regex("\\b(roz|daily|har din|every day|everyday)\\b").containsMatchIn(t)) days = (1..7).toList()
        else {
            val named = dayWords.filter { Regex("\\b${it.key}\\b").containsMatchIn(t) }.values.distinct()
            if (named.isNotEmpty() && (repeatWord || true)) days = named.sorted()
        }
        // "10 minute baad alarm"
        rel.find(t)?.let { m ->
            val n = m.groupValues[1].toInt(); val mins = if (m.groupValues[2].startsWith("h") || m.groupValues[2].startsWith("g")) n * 60 else n
            if (mins in 1..1440) return Parsed(0, 0, false, mins, emptyList(), false)
        }
        val m = clock.findAll(t).firstOrNull { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() || t.trim().let { _ -> true } } ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return null
        val am = Regex("\\b(subah|savere|morning|am)\\b").containsMatchIn(t) || m.groupValues[3] == "am"
        val pm = Regex("\\b(shaam|sham|evening|dopahar|afternoon|pm)\\b").containsMatchIn(t) || m.groupValues[3] == "pm"
        val night = Regex("\\b(raat|night)\\b").containsMatchIn(t)
        if (h >= 13 || h == 0) return Parsed(h, min, false, null, days, tomorrow)
        val hour = when {
            am -> h % 12
            pm -> h % 12 + 12
            night -> if (h == 12) 0 else if (h >= 6) h + 12 else h
            else -> return Parsed(h % 12, min, true, null, days, tomorrow) // ambiguous: caller must ask
        }
        return Parsed(hour, min, false, null, days, tomorrow)
    }

    fun timerSeconds(raw: String): Int? {
        val m = Regex("(\\d+)\\s*(second|seconds|sec|secs|minute|minutes|min|mins|ghante|ghanta|hour|hours|hr)\\b").find(raw.lowercase()) ?: return null
        val n = m.groupValues[1].toInt(); val u = m.groupValues[2]
        val s = when { u.startsWith("sec") -> n; u.startsWith("min") -> n * 60; else -> n * 3600 }
        return s.takeIf { it in 1..86400 }
    }
}
