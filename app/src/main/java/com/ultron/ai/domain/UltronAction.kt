package com.ultron.ai.domain

/** Typed actions. The AI/parser may PROPOSE one; execution always passes ActionValidator first. */
sealed interface UltronAction {
    data class OpenApp(val appName: String) : UltronAction
    data class YouTubeSearch(val query: String) : UltronAction
    data class OpenSystemSettings(val target: SettingsTarget) : UltronAction
    data class SetReminder(val text: String, val delayMinutes: Int) : UltronAction
    data class Remember(val text: String) : UltronAction
    data object StopSpeaking : UltronAction

    // ---- Screen control (needs the user to enable ULTRON's Accessibility service) ----
    data class Click(val label: String) : UltronAction
    data class TypeText(val text: String) : UltronAction
    data class Scroll(val down: Boolean) : UltronAction
    data class Navigate(val kind: NavKind) : UltronAction
    data object ReadScreen : UltronAction
    /** Multi-step goal carried out by the screen agent, one confirmed-if-risky step at a time. */
    data class ScreenTask(val goal: String) : UltronAction
}

enum class NavKind { BACK, HOME, RECENTS, NOTIFICATIONS }

enum class SettingsTarget { MAIN, WIFI, BLUETOOTH, DISPLAY, SOUND, APP_NOTIFICATIONS }

/**
 * Consequential actions (calls, messages, purchases, deletions) are NOT implemented in this
 * build. The policy hook exists so any future one must declare it and be confirmed first.
 */
enum class Consequence { SAFE, CONFIRM_FIRST }

val UltronAction.consequence: Consequence
    get() = Consequence.SAFE // risky taps are confirmed per step by RiskPolicy inside the screen executor
