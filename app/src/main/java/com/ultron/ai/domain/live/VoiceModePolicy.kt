package com.ultron.ai.domain.live

enum class VoiceMode {
    /** Mic open ONLY for the offline wake matcher; the conversation engine starts after a wake phrase. */
    ARMED,
    /** Gemini Live phone-call style conversation (streams all audio). */
    CALL,
    /** Normal turn-based live conversation. */
    NORMAL,
    /** "Wake word" is switched on but nothing can listen for it yet: start NOTHING (never fall back to another voice mode). */
    NEEDS_WAKE_SETUP,
}

/**
 * The single decision about which voice mode a mic start uses. Root cause of the reported conflict: with "Offline wake word"
 * ON but no wake recordings, the old inline rule simply computed armed=false and silently started the Gemini phone-call mode.
 */
object VoiceModePolicy {
    fun decide(
        wakeOn: Boolean, wakeReady: Boolean, cloudReady: Boolean, wakeFirst: Boolean,
        liveCallEnabled: Boolean, callAvailable: Boolean, forceNormal: Boolean,
    ): VoiceMode {
        if (wakeOn && !wakeReady) return VoiceMode.NEEDS_WAKE_SETUP
        val armed = wakeOn && wakeReady && (!cloudReady || wakeFirst)
        if (armed) return VoiceMode.ARMED
        val call = callAvailable && liveCallEnabled && cloudReady && !forceNormal
        return if (call) VoiceMode.CALL else VoiceMode.NORMAL
    }
}
