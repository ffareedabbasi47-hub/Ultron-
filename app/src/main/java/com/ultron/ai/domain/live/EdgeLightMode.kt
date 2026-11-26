package com.ultron.ai.domain.live

/**
 * How the system-wide edge light looks for each real session state. Pure data so the mapping can be unit tested;
 * the Android view only reads these numbers. Frame rates are deliberately low for calm states (battery).
 */
enum class EdgeMode(
    /** Time for the glow to travel once around the screen. 0 = no rotation. */
    val periodMs: Long,
    /** Stroke width in dp. */
    val widthDp: Float,
    /** Overall brightness 0..1. */
    val intensity: Float,
    /** Redraw rate cap. */
    val fps: Int,
    /** Slow breathing of the brightness (thinking). */
    val pulse: Boolean = false,
) {
    OFF(0L, 0f, 0f, 0),
    /** Armed, waiting for the wake word: dim and slow. */
    IDLE(9000L, 2.5f, 0.35f, 20),
    LISTENING(4200L, 3.5f, 0.75f, 30),
    USER_SPEECH(2400L, 4.5f, 0.95f, 45),
    THINKING(6000L, 3.5f, 0.6f, 30, pulse = true),
    SPEAKING(1800L, 5f, 1f, 45),
    /** Transient activation burst after a wake word; the view returns to the real mode by itself. */
    WAKE(900L, 7f, 1f, 60);
}

object EdgeModes {
    const val WAKE_FLASH_MS = 1100L

    /** [micRunning] must be the real recorder state, not a guess. */
    fun of(state: LiveState, micRunning: Boolean): EdgeMode = when (state) {
        LiveState.LISTENING -> EdgeMode.LISTENING
        LiveState.USER_SPEECH -> EdgeMode.USER_SPEECH
        LiveState.THINKING -> EdgeMode.THINKING
        LiveState.SPEAKING -> EdgeMode.SPEAKING
        LiveState.SLEEP -> if (micRunning) EdgeMode.IDLE else EdgeMode.OFF
        LiveState.STOPPED, LiveState.STARTING, LiveState.PAUSED, LiveState.PERMISSION_REQUIRED, LiveState.AUDIO_ERROR -> EdgeMode.OFF
    }
}
