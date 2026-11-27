package com.ultron.ai.domain.live

/**
 * Turns Gemini Live's fragmentary transcripts into whole chat messages, in order, once each.
 * heard = inputTranscription fragments (user's speech), said = outputTranscription fragments (ULTRON).
 * Nothing is invented: a message exists only if the Live API actually sent text for it.
 */
class LiveTurnAssembler {
    data class Msg(val role: String, val text: String)

    private val user = StringBuilder()
    private val model = StringBuilder()

    fun feed(heard: String?, said: String?, turnComplete: Boolean, interrupted: Boolean): List<Msg> {
        val out = ArrayList<Msg>(2)
        if (!heard.isNullOrEmpty()) user.append(heard)
        if (!said.isNullOrEmpty()) {
            if (user.isNotBlank()) { out += Msg("user", user.toString().trim()); user.clear() } // user line comes before the answer
            model.append(said)
        }
        if (turnComplete || interrupted) {
            if (user.isNotBlank()) { out += Msg("user", user.toString().trim()) }
            user.clear()
            if (model.isNotBlank()) out += Msg("assistant", model.toString().trim())
            model.clear()
        }
        return out
    }

    fun reset() { user.clear(); model.clear() }
}
