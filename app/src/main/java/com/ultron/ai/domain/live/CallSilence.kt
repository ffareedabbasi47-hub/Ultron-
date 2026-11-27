package com.ultron.ai.domain.live

enum class CallSilenceAction { NONE, PAUSE, RESUME }

/**
 * Phone-call priority (highest of all audio states). While a phone call is active ULTRON is silent: the engine is paused
 * (mic released, no TTS, no wake reaction). When the call is really over it resumes ONLY if it was listening before the call.
 * Pure state machine so the rule is unit-tested.
 */
class CallSilence {
    private var suppressed = false
    private var resumeWanted = false
    val inCallSilence: Boolean get() = suppressed

    /** [engineRunning] = the engine was listening/armed/conversing (not stopped, not paused by the user). */
    fun onCallState(inCall: Boolean, engineRunning: Boolean): CallSilenceAction {
        if (inCall && !suppressed) {
            suppressed = true; resumeWanted = engineRunning
            return if (engineRunning) CallSilenceAction.PAUSE else CallSilenceAction.NONE
        }
        if (!inCall && suppressed) {
            suppressed = false
            val r = resumeWanted; resumeWanted = false
            return if (r) CallSilenceAction.RESUME else CallSilenceAction.NONE
        }
        return CallSilenceAction.NONE
    }

    /** The user explicitly stopped ULTRON during the call: never auto-resume it. */
    fun userStopped() { resumeWanted = false }
}
