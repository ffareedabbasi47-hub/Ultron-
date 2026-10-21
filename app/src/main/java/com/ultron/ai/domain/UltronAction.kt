package com.ultron.ai.domain

/** Typed actions. The AI/parser may PROPOSE one; execution always passes ActionValidator first. */
sealed interface UltronAction {
    data class OpenApp(val appName: String) : UltronAction
    data class YouTubeSearch(val query: String) : UltronAction
    data class OpenSystemSettings(val target: SettingsTarget) : UltronAction
    data class SetReminder(val text: String, val delayMinutes: Int) : UltronAction
    data class Remember(val text: String) : UltronAction
    data object StopSpeaking : UltronAction
}

enum class SettingsTarget { MAIN, WIFI, BLUETOOTH, DISPLAY, SOUND, APP_NOTIFICATIONS }

/**
 * Consequential actions (calls, messages, purchases, deletions) are NOT implemented in this
 * build. The policy hook exists so any future one must declare it and be confirmed first.
 */
enum class Consequence { SAFE, CONFIRM_FIRST }

val UltronAction.consequence: Consequence
    get() = Consequence.SAFE
