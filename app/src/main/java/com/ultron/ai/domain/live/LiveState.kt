package com.ultron.ai.domain.live

/** Every state the live session can really be in. The UI shows exactly this, nothing invented. */
enum class LiveState(val label: String, val micOpen: Boolean) {
    STOPPED("STOPPED", false),
    STARTING("STARTING", false),
    LISTENING("LISTENING", true),
    USER_SPEECH("USER SPEECH", true),
    THINKING("THINKING", true),
    SPEAKING("ULTRON SPEAKING", true),
    SLEEP("SLEEP / ARMED", true),
    PAUSED("PAUSED", false),
    PERMISSION_REQUIRED("PERMISSION REQUIRED", false),
    AUDIO_ERROR("AUDIO ERROR", false),
}

/** Allowed transitions; anything else is rejected so the state can never "flicker" into nonsense. */
object LiveTransitions {
    private val allowed: Map<LiveState, Set<LiveState>> = mapOf(
        LiveState.STOPPED to setOf(LiveState.STARTING),
        LiveState.STARTING to setOf(LiveState.LISTENING, LiveState.PERMISSION_REQUIRED, LiveState.AUDIO_ERROR, LiveState.STOPPED),
        LiveState.LISTENING to setOf(LiveState.USER_SPEECH, LiveState.SLEEP, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.THINKING, LiveState.STOPPED),
        LiveState.USER_SPEECH to setOf(LiveState.LISTENING, LiveState.THINKING, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.STOPPED),
        LiveState.THINKING to setOf(LiveState.SPEAKING, LiveState.LISTENING, LiveState.USER_SPEECH, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.STOPPED),
        LiveState.SPEAKING to setOf(LiveState.LISTENING, LiveState.USER_SPEECH, LiveState.THINKING, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.STOPPED),
        LiveState.SLEEP to setOf(LiveState.LISTENING, LiveState.USER_SPEECH, LiveState.THINKING, LiveState.PAUSED, LiveState.AUDIO_ERROR, LiveState.STOPPED),
        LiveState.PAUSED to setOf(LiveState.STARTING, LiveState.LISTENING, LiveState.STOPPED),
        LiveState.PERMISSION_REQUIRED to setOf(LiveState.STARTING, LiveState.STOPPED),
        LiveState.AUDIO_ERROR to setOf(LiveState.STARTING, LiveState.STOPPED),
    )

    fun canMove(from: LiveState, to: LiveState): Boolean = from == to || to in (allowed[from] ?: emptySet())
}

/** User-selectable inactivity timeout ("Chup rehne par Live Session Sleep"). */
enum class SleepTimeout(val label: String, val minutes: Int) {
    OFF("Off", 0), M5("5 minutes", 5), M10("10 minutes", 10), M15("15 minutes", 15), M20("20 minutes", 20);

    val millis: Long get() = minutes * 60_000L
}

/**
 * Inactivity clock on a monotonic time base. It only runs while the session is idle-listening:
 * it is paused during thinking/speaking, and ONLY verified user speech resets it. Assistant TTS,
 * media playback and noise never reset it because callers must pass `userSpeech = true` only for
 * genuine, accepted user utterances.
 */
class SleepClock(private val timeoutMs: Long) {
    private var idleSince = -1L
    private var lastTick = -1L

    fun reset(nowMs: Long) { idleSince = nowMs; lastTick = nowMs }

    /** Returns true exactly when the session should go to SLEEP. */
    fun tick(nowMs: Long, userSpeech: Boolean, busy: Boolean): Boolean {
        if (timeoutMs <= 0L) return false
        if (idleSince < 0L || userSpeech || busy) { idleSince = nowMs; lastTick = nowMs; return false }
        lastTick = nowMs
        return nowMs - idleSince >= timeoutMs
    }

    /** Accurate "sleep in" value for the UI, or null when Off / not running. */
    fun remainingMs(nowMs: Long): Long? = if (timeoutMs <= 0L || idleSince < 0L) null else (timeoutMs - (nowMs - idleSince)).coerceAtLeast(0L)
}
