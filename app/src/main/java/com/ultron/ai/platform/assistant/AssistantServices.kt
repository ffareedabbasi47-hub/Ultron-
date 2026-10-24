package com.ultron.ai.platform.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.ultron.ai.MainActivity

/** Registers ULTRON as a selectable digital assistant (Android Settings > Default apps > Digital assistant). */
class UltronInteractionService : VoiceInteractionService()

class UltronSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = UltronSession(this)
}

/** Power-button / corner-swipe / "assist" gesture: open ULTRON and start the live session. */
class UltronSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_LIVE_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startAssistantActivity(i)
        hide()
    }
}

/**
 * Android requires an assistant to name a recognition service. ULTRON does its own speech recognition inside the
 * live session, so this one never recognises anything: it always reports an error instead of pretending to work.
 */
class UltronRecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {} }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
