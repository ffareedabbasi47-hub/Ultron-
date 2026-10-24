package com.ultron.ai.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One thing visible on screen, as reported by Android's accessibility tree. */
data class ScreenNode(
    val id: Int,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
)

data class ScreenSnapshot(val packageName: String, val nodes: List<ScreenNode>) {
    fun describe(maxNodes: Int = 70): String = buildString {
        append("app: ").append(packageName).append('\n')
        nodes.take(maxNodes).forEach { n ->
            append('[').append(n.id).append("] ").append(n.label.take(60))
            val tags = buildList { if (n.clickable) add("tap"); if (n.editable) add("input"); if (n.scrollable) add("scroll") }
            if (tags.isNotEmpty()) append(" (").append(tags.joinToString(",")).append(')')
            append('\n')
        }
    }
}

/** The platform side of screen control (implemented with an AccessibilityService). */
interface ScreenControl {
    fun isEnabled(): Boolean
    fun snapshot(): ScreenSnapshot?
    fun clickNode(id: Int): Boolean
    fun clickLabel(label: String): Boolean
    fun typeText(text: String): Boolean
    fun scroll(down: Boolean): Boolean
    fun navigate(kind: NavKind): Boolean
}

/** Decides which on-screen taps need an explicit "haan" first. Conservative on purpose. */
object RiskPolicy {
    private val risky = Regex(
        "\\b(send|bhej|bhejo|pay|payment|buy|purchase|order|place order|checkout|confirm|transfer|delete|remove|uninstall|call|dial|post|publish|submit|" +
            "sign out|log out|logout|unsubscribe|erase|format|reset|install|allow|grant|accept|agree|ok to proceed)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val money = Regex("(₹|rs\\.?|inr|\\$|usd)\\s*\\d", RegexOption.IGNORE_CASE)

    fun needsConfirmation(label: String): Boolean = risky.containsMatchIn(label) || money.containsMatchIn(label)

    /** Sensitive packages where ULTRON never acts automatically. */
    private val blockedPackages = listOf(
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.settings.password",
    )
    fun packageBlocked(pkg: String) = blockedPackages.any { pkg.startsWith(it) }
}

sealed interface AgentStep {
    data class Click(val id: Int?, val label: String?) : AgentStep
    data class Type(val text: String) : AgentStep
    data class Scroll(val down: Boolean) : AgentStep
    data class Nav(val kind: NavKind) : AgentStep
    data class Open(val app: String) : AgentStep
    data class Done(val message: String) : AgentStep
    data class Fail(val message: String) : AgentStep
}

/** Prompt + strict JSON parsing for the bounded screen agent. Pure, so it is unit-tested. */
object AgentProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val SYSTEM =
        "You control an Android phone through its accessibility tree to finish ONE user goal. " +
            "Each turn you get the screen as a numbered list. Reply with ONE JSON object and nothing else. " +
            "Actions: {\"action\":\"open\",\"app\":\"<app name>\"} | {\"action\":\"click\",\"id\":<number>} | " +
            "{\"action\":\"type\",\"text\":\"...\"} | {\"action\":\"scroll\",\"dir\":\"down|up\"} | " +
            "{\"action\":\"back\"} | {\"action\":\"home\"} | {\"action\":\"done\",\"message\":\"short result in Hinglish\"} | " +
            "{\"action\":\"fail\",\"message\":\"why it cannot be done\"}. " +
            "Rules: never enter passwords, OTPs or payment details; never bypass lock screens or security prompts; " +
            "prefer 'open' to start an app; use only ids from the list; if the goal is finished say done; if stuck say fail."

    fun prompt(goal: String, screen: String, history: List<String>): String = buildString {
        append("GOAL: ").append(goal).append("\n\n")
        if (history.isNotEmpty()) append("STEPS SO FAR:\n").append(history.takeLast(8).joinToString("\n")).append("\n\n")
        append("SCREEN:\n").append(screen)
    }

    fun parse(raw: String): AgentStep {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return AgentStep.Fail("Model ka jawab samajh nahi aaya.")
        val o: JsonObject = try { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject } catch (_: Exception) { return AgentStep.Fail("Model ka jawab samajh nahi aaya.") }
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return when (str("action")?.lowercase()) {
            "click" -> AgentStep.Click(str("id")?.toDoubleOrNull()?.toInt(), str("label"))
            "type" -> str("text")?.takeIf { it.isNotEmpty() && it.length <= 300 }?.let { AgentStep.Type(it) } ?: AgentStep.Fail("Type ka text galat.")
            "scroll" -> AgentStep.Scroll(str("dir")?.lowercase() != "up")
            "back" -> AgentStep.Nav(NavKind.BACK)
            "home" -> AgentStep.Nav(NavKind.HOME)
            "open" -> str("app")?.takeIf { it.isNotBlank() }?.let { AgentStep.Open(it) } ?: AgentStep.Fail("App ka naam nahi mila.")
            "done" -> AgentStep.Done(str("message") ?: "Ho gaya.")
            "fail" -> AgentStep.Fail(str("message") ?: "Nahi ho paya.")
            else -> AgentStep.Fail("Model ne unknown action diya.")
        }
    }

    private val yes = Regex("^(?:haan|han|ha|haa|yes|yeah|ok|okay|kar do|karo|kardo|theek hai|sure|confirm)\\b")
    private val no = Regex("^(?:nahi|nahin|na|no|mat|ruk|ruko|stop|cancel|band)\\b")

    /** true = yes, false = no, null = unclear. */
    fun yesNo(text: String): Boolean? {
        val t = text.trim().lowercase().trim('.', '!', '?', ',')
        if (no.containsMatchIn(t)) return false
        if (yes.containsMatchIn(t)) return true
        return null
    }
}
