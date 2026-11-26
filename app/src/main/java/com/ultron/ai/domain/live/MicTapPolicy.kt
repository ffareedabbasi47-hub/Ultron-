package com.ultron.ai.domain.live

enum class MicTapAction { IGNORE, STOP, START, REQUEST_PERMISSION }

/**
 * What one tap on the microphone button means. One tap starts; while the session is starting taps are ignored
 * (never a second start); a tap on a running session is the user's explicit Stop.
 */
object MicTapPolicy {
    fun action(state: LiveState, permissionGranted: Boolean): MicTapAction = when (state) {
        LiveState.STARTING -> MicTapAction.IGNORE
        LiveState.LISTENING, LiveState.USER_SPEECH, LiveState.THINKING, LiveState.SPEAKING -> MicTapAction.STOP
        LiveState.STOPPED, LiveState.SLEEP, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.PERMISSION_REQUIRED ->
            if (permissionGranted) MicTapAction.START else MicTapAction.REQUEST_PERMISSION
    }
}

/** Swallows accidental double / rapid taps so start-stop-start-stop flicker cannot happen. */
class TapGuard(private val minGapMs: Long = 700L) {
    private var last = Long.MIN_VALUE
    fun accept(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < minGapMs) return false
        last = nowMs
        return true
    }
}
