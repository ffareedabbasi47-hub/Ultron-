package com.ultron.ai.domain

data class ActionResult(val success: Boolean, val message: String)

/** Platform side of the typed action layer (implemented with Android intents/APIs). */
interface ActionRunner {
    suspend fun run(action: UltronAction): ActionResult
}
