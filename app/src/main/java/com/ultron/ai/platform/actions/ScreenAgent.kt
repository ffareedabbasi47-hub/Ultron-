package com.ultron.ai.platform.actions

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.GeminiOneShot
import com.ultron.ai.domain.AgentProtocol
import com.ultron.ai.domain.AgentStep
import com.ultron.ai.domain.RiskPolicy
import com.ultron.ai.domain.ScreenControl
import com.ultron.ai.domain.UltronAction
import com.ultron.ai.domain.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Bounded screen agent. Loop (max [MAX_STEPS]): read the accessibility tree, ask Gemini for ONE step, validate,
 * confirm if risky, execute through [ScreenControl], re-read, repeat. It stops on done/fail, step limit,
 * cancellation, a blocked app, or when the screen stops changing. It never types into password fields and
 * asks "haan/nahi" before anything that sends, pays, deletes, calls or confirms.
 */
class ScreenAgent(
    private val screen: ScreenControl,
    private val ai: GeminiOneShot,
    private val broker: ConfirmationBroker,
    private val openApp: suspend (String) -> ActionResult,
    private val speak: (String) -> Unit,
    private val diagnostics: DiagnosticsLog,
) {
    @Volatile var running = false
        private set

    suspend fun run(goal: String): ActionResult {
        if (!screen.isEnabled()) return ActionResult(false, "Screen control off hai. Menu → Assistant & permissions mein ULTRON ka Accessibility service on karo.")
        if (running) return ActionResult(false, "Pehla screen task abhi chal raha hai.")
        running = true
        try {
            val history = ArrayList<String>()
            var lastScreen = ""
            var same = 0
            for (step in 1..MAX_STEPS) {
                if (!coroutineContext.isActive) return ActionResult(false, "Task rok diya.")
                val snap = screen.snapshot()
                if (snap != null && RiskPolicy.packageBlocked(snap.packageName)) return ActionResult(false, "Is screen (security/installer) par main khud kuch nahi karta. Aap karo.")
                val desc = snap?.describe() ?: "(screen padh nahi paya: shayad secure ya khaali)"
                same = if (desc == lastScreen) same + 1 else 0
                lastScreen = desc
                if (same >= 3) return ActionResult(false, "Screen badal nahi rahi, isliye ruk gaya.")
                val raw = ai.generate(AgentProtocol.SYSTEM, AgentProtocol.prompt(goal, desc, history))
                    ?: return ActionResult(false, ai.lastError ?: "AI se jawab nahi mila.")
                val action = AgentProtocol.parse(raw)
                diagnostics.log("agent", "step $step ${action::class.simpleName}")
                when (action) {
                    is AgentStep.Done -> return ActionResult(true, action.message)
                    is AgentStep.Fail -> return ActionResult(false, action.message)
                    is AgentStep.Open -> {
                        val r = openApp(action.app)
                        history += "$step: open ${action.app} -> ${if (r.success) "ok" else "failed"}"
                        if (!r.success) return r
                    }
                    is AgentStep.Nav -> { val ok = screen.navigate(action.kind); history += "$step: ${action.kind} -> ${if (ok) "ok" else "failed"}" }
                    is AgentStep.Scroll -> { val ok = screen.scroll(action.down); history += "$step: scroll -> ${if (ok) "ok" else "nothing scrollable"}" }
                    is AgentStep.Type -> {
                        val ok = screen.typeText(action.text)
                        history += "$step: type -> ${if (ok) "ok" else "no input field"}"
                    }
                    is AgentStep.Click -> {
                        val node = action.id?.let { id -> snap?.nodes?.firstOrNull { it.id == id } }
                        val label = node?.label ?: action.label.orEmpty()
                        if (label == "(password field)") return ActionResult(false, "Password field ko main nahi chhoota.")
                        if (RiskPolicy.needsConfirmation(label)) {
                            val yes = broker.ask("\"$label\" dabane wala hoon. Karun? Haan ya nahi bolo.", speak)
                            if (!yes) return ActionResult(false, "Theek hai, \"$label\" nahi dabaya. Task roka.")
                        }
                        val ok = if (action.id != null && node != null) screen.clickNode(action.id) else if (label.isNotBlank()) screen.clickLabel(label) else false
                        history += "$step: click \"$label\" -> ${if (ok) "ok" else "failed"}"
                    }
                }
                delay(STEP_SETTLE_MS) // let the app react before re-reading the screen
            }
            return ActionResult(false, "$MAX_STEPS step mein task poora nahi hua, isliye ruk gaya. Screen par check kar lo.")
        } finally { running = false }
    }

    companion object { const val MAX_STEPS = 12; const val STEP_SETTLE_MS = 800L }
}
