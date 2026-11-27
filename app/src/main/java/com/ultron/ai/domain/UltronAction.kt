package com.ultron.ai.domain

/** Typed actions. The AI/parser may PROPOSE one; execution always passes ActionValidator first. */
sealed interface UltronAction {
    data class OpenApp(val appName: String) : UltronAction
    /** Searches YouTube and PLAYS the first matching video (falls back to the results page, and says so). */
    data class YouTubeSearch(val query: String) : UltronAction
    /** Turn a phone switch on/off (Wi-Fi, hotspot, Bluetooth...). Needs the Accessibility service except for the torch. */
    /** Look the person up in Contacts, confirm out loud, then call. Never calls an unconfirmed or ambiguous match. */
    data class CallContact(val name: String) : UltronAction
    data class CallNumber(val number: String) : UltronAction
    data object OpenDialer : UltronAction
    data object EndCall : UltronAction
    /** [inMinutes] set = relative ("10 minute baad"); else clock time ([ambiguous] = AM/PM not said, ask). Days use Calendar numbers. */
    data class SetAlarm(val hour: Int, val minute: Int, val ambiguous: Boolean, val inMinutes: Int?, val days: List<Int>, val tomorrow: Boolean) : UltronAction
    data class SetTimer(val seconds: Int) : UltronAction
    data object ShowAlarms : UltronAction
    data object DismissAlarm : UltronAction
    /** Message to a contact (SMS, or WhatsApp). Always previewed and confirmed before sending. */
    data class SendMessage(val name: String, val body: String?, val whatsapp: Boolean) : UltronAction
    data class AdjustVolume(val up: Boolean) : UltronAction
    /** [percent] = absolute 1..100, or null with [delta] (+/- percentage points). */
    data class SetBrightness(val percent: Int?, val delta: Int = 0) : UltronAction
    data class SetDnd(val on: Boolean) : UltronAction
    data class MediaControl(val cmd: MediaCmd) : UltronAction
    data class SetToggle(val target: ToggleTarget, val on: Boolean) : UltronAction
    data class OpenSystemSettings(val target: SettingsTarget) : UltronAction
    data class SetReminder(val text: String, val delayMinutes: Int) : UltronAction
    data class Remember(val text: String) : UltronAction
    data object StopSpeaking : UltronAction
    /** Hand a coding/website/app request to the background Builder agent. */
    data class BuildProject(val brief: String) : UltronAction

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

enum class SettingsTarget {
    MAIN, WIFI, BLUETOOTH, DISPLAY, SOUND, APP_NOTIFICATIONS,
    HOTSPOT, AIRPLANE, MOBILE_DATA, LOCATION, BATTERY, APPS, STORAGE, NFC, DATE, LANGUAGE, ACCESSIBILITY, SECURITY, NETWORK, DND
}

enum class MediaCmd { PLAY, PAUSE, NEXT, PREVIOUS, CLOSE_YOUTUBE }

enum class ToggleTarget { WIFI, HOTSPOT, BLUETOOTH, AIRPLANE, MOBILE_DATA, LOCATION, NFC, TORCH }

/**
 * Consequential actions (calls, messages, purchases, deletions) are NOT implemented in this
 * build. The policy hook exists so any future one must declare it and be confirmed first.
 */
enum class Consequence { SAFE, CONFIRM_FIRST }

val UltronAction.consequence: Consequence
    get() = Consequence.SAFE // risky taps are confirmed per step by RiskPolicy inside the screen executor
