package com.ultron.ai.domain

/**
 * Answers creator-identity questions locally so the reply is identical online, offline,
 * in voice and in text, and across app restarts.
 *
 * This is application-configured attribution (see the master spec), NOT independent proof
 * of authorship. The creator name comes from BuildConfig.CREATOR_NAME.
 */
class IdentityResponder(private val creator: String) {

    private val challenge = Regex(
        "(?i)\\b(maine|mene|main\\s*ne)\\b.*\\b(banaya|banaya\\s+hai|bnaya|created|made|built)\\b",
    )

    private val creatorQuestions = listOf(
        Regex("(?i)\\bkis\\s*ne\\b.*\\b(banaya|bnaya|develop|create|created|made|built|likha)\\b"),
        Regex("(?i)\\bwho\\s+(created|made|built|developed|programmed)\\s+(you|u)\\b"),
        Regex("(?i)\\b(tujhe|tumhe|aapko|apko)\\s+kis\\s*ne\\b"),
        Regex("(?i)\\b(tera|tumhara|aapka|apka|your)\\s+(creator|developer|maker|owner)\\b"),
        Regex("(?i)\\bwho\\s+is\\s+your\\s+(creator|developer|maker)\\b"),
    )

    fun respond(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160) return null
        if (challenge.containsMatchIn(t)) {
            return "Mere configured creator record ke mutabik mujhe $creator ne develop kiya hai."
        }
        if (creatorQuestions.any { it.containsMatchIn(t) }) {
            return "Mujhe $creator ne banaya hai."
        }
        return null
    }
}
