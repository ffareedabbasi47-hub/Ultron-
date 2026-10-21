package com.ultron.ai.domain

data class Validation(val ok: Boolean, val reason: String? = null) {
    companion object {
        val OK = Validation(true)
        fun no(reason: String) = Validation(false, reason)
    }
}

/** Every action is validated before any platform API is touched. */
object ActionValidator {
    private val appNameChars = Regex("^[\\p{L}\\p{N} .&'+\\-]{1,40}$")
    private const val MAX_REMINDER_MINUTES = 7 * 24 * 60

    fun validate(action: UltronAction): Validation = when (action) {
        is UltronAction.OpenApp ->
            if (appNameChars.matches(action.appName)) Validation.OK
            else Validation.no("App ka naam valid nahi hai.")

        is UltronAction.YouTubeSearch ->
            if (action.query.isBlank()) Validation.no("Search query khaali hai.")
            else if (action.query.length > 120) Validation.no("Search query bahut lambi hai (max 120).")
            else Validation.OK

        is UltronAction.OpenSystemSettings -> Validation.OK

        is UltronAction.SetReminder ->
            if (action.text.isBlank() || action.text.length > 200) Validation.no("Reminder text 1-200 characters ka hona chahiye.")
            else if (action.delayMinutes < 1) Validation.no("Reminder ka time samajh nahi aaya (kam se kam 1 minute).")
            else if (action.delayMinutes > MAX_REMINDER_MINUTES) Validation.no("Reminder max 7 din aage tak set ho sakta hai.")
            else Validation.OK

        is UltronAction.Remember ->
            if (action.text.isBlank() || action.text.length > 500) Validation.no("Yaad rakhne wala text 1-500 characters ka hona chahiye.")
            else Validation.OK

        UltronAction.StopSpeaking -> Validation.OK
    }
}
