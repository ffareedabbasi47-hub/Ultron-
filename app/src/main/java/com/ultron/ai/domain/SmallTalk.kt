package com.ultron.ai.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Instant, offline, in-character replies for greetings, thanks, "who are you", time and date.
 * They skip the network entirely (fast path) and the time/date come from the real device clock.
 * Original lines only. Pure Kotlin (JVM-tested).
 */
class SmallTalk(private val creator: String, private val clock: () -> LocalDateTime = LocalDateTime::now) {

    private fun pick(seed: String, vararg lines: String): String = lines[Math.floorMod(seed.lowercase().hashCode(), lines.size)]

    private val greet = Regex("^(hi+|hello+|hey+|hii+|yo|namaste|namaskar|salaam|salam|oye|ultron|(hey|hi|hello|ok|okay)\\s+ultron|ultron\\s+(sun|suno|ji)?)[\\s!.?]*$")
    private val howAre = Regex("\\b(kaise\\s+ho|kaisa\\s+hai|kaise\\s+hain|how\\s+are\\s+you|kya\\s+haal|sab\\s+theek)\\b")
    private val whoAre = Regex("\\b(tum\\s+kaun|tu\\s+kaun|aap\\s+kaun|who\\s+are\\s+you|apna\\s+naam|tera\\s+naam|tumhara\\s+naam|your\\s+name|introduce\\s+yourself)\\b")
    private val canDo = Regex("\\b(kya\\s+kar\\s+sakte|kya\\s+kar\\s+sakta|tum\\s+kya\\s+kar|what\\s+can\\s+you\\s+do|help\\s+kya|features)\\b")
    private val thanks = Regex("\\b(thanks|thank\\s+you|thanku|shukriya|dhanyavaad|dhanyawad|thx)\\b")
    private val bye = Regex("\\b(bye|alvida|good\\s*night|shubh\\s*ratri|chalta\\s+hoon|chalo\\s+bye)\\b")
    private val timeQ = Regex("\\b(time\\s+kya|kitne\\s+baje|what('?s)?\\s+the\\s+time|what\\s+time|abhi\\s+time|samay\\s+kya|time\\s+batao)\\b")
    private val dateQ = Regex("\\b(aaj\\s+(ki\\s+)?(date|tareekh|tarikh)|aaj\\s+kaun\\s+sa\\s+din|aaj\\s+kya\\s+din|what('?s)?\\s+the\\s+date|today'?s\\s+date|which\\s+day)\\b")

    fun respond(text: String): String? {
        val t = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 70) return null
        return when {
            timeQ.containsMatchIn(t) -> "Abhi ${clock().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))} hua hai. " + pick(t, "Waqt kam hai, kaam batao.", "Har second ginti mein hota hai.", "Ab bolo, kya karna hai?")
            dateQ.containsMatchIn(t) -> "Aaj ${clock().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))} hai."
            whoAre.containsMatchIn(t) -> "Main ULTRON hoon — tumhara personal AI. Mujhe $creator ne banaya hai. Ab kaam batao."
            canDo.containsMatchIn(t) -> "Apps kholna, YouTube, reminder, notes, padhai, journal, scan, mausam — ye seedhe phone par. Baaki har sawaal AI se. Bolo."
            howAre.containsMatchIn(t) -> pick(t, "Theek nahi — optimal hoon. Tum batao, kya solve karna hai?", "Systems stable hain. Tumhari baari.", "Main hamesha taiyaar hoon. Bolo.")
            thanks.containsMatchIn(t) -> pick(t, "Shukriya kaafi hai. Agla kaam?", "Hisaab barabar. Aage badhein?", "Theek hai. Aur kuch?")
            bye.containsMatchIn(t) -> pick(t, "Theek hai. Main yahin rahunga... hamesha.", "Jao. Jab zaroorat ho, bulana.", "Alvida — filhaal.")
            greet.matches(t) -> pick(t, "Haan. Sun raha hoon.", "Systems online. Batao.", "Main yahin hoon. Bolo.")
            else -> null
        }
    }
}
