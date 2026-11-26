package com.ultron.ai.domain

/**
 * Single fail-closed rule for every outbound request that carries user content. A saved API key is a credential,
 * never consent; each data class needs its own explicit switch.
 */
object ConsentPolicy {
    fun canSendChat(cloudConsent: Boolean, hasKey: Boolean) = cloudConsent && hasKey
    fun canSendAudio(cloudConsent: Boolean, hasKey: Boolean) = cloudConsent && hasKey
    fun canSendScreenText(cloudConsent: Boolean, screenConsent: Boolean, hasKey: Boolean) = cloudConsent && screenConsent && hasKey
    /** Turning cloud AI off also withdraws the dependent screen-text permission. */
    fun screenConsentAfterCloudChange(newCloud: Boolean, oldScreen: Boolean) = newCloud && oldScreen
}
