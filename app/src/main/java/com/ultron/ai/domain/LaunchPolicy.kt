package com.ultron.ai.domain

/**
 * Who may start the microphone through a launch intent. MainActivity is exported (the system assistant gesture
 * needs ASSIST / VOICE_COMMAND), so ANY app can send those intents. They may open ULTRON, but only an intent
 * carrying the per-process secret that UltronSession (the system-bound assistant session) adds may auto-start
 * listening. Everything else needs the user to tap the mic button.
 */
object LaunchPolicy {
    fun autoStartAllowed(presented: String?, expected: String?): Boolean {
        if (presented.isNullOrEmpty() || expected.isNullOrEmpty() || presented.length != expected.length) return false
        var diff = 0
        for (i in presented.indices) diff = diff or (presented[i].code xor expected[i].code)
        return diff == 0
    }

    fun newToken(): String = java.util.UUID.randomUUID().toString()
}
