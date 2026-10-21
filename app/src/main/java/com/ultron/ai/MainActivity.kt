package com.ultron.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ultron.ai.data.settings.UltronSettings
import com.ultron.ai.ui.AppRoot
import com.ultron.ai.ui.LocalContainer
import com.ultron.ai.ui.theme.UiPrefs
import com.ultron.ai.ui.theme.UltronTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as UltronApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent {
            // null until DataStore has really loaded, so TTS starts with the saved engine/voice.
            val loaded by c.settings.settings.collectAsState(initial = null)
            val s: UltronSettings = loaded ?: UltronSettings()

            if (loaded != null) {
                // (Re)create the TTS engine only when the chosen engine changes.
                LaunchedEffect(s.ttsEngine) { c.tts.initialize(s) }
                // Re-apply saved voice/rate/pitch/language whenever they change or the engine becomes ready.
                val ttsReady = c.tts.state.collectAsState().value.ready
                LaunchedEffect(ttsReady, s.ttsVoice, s.ttsRate, s.ttsPitch, s.ttsVolume, s.language) {
                    if (ttsReady) c.tts.applySettings(s)
                }
            }

            val prefs = remember(s.reducedMotion, s.lowPerformance) { UiPrefs(s.reducedMotion, s.lowPerformance) }
            CompositionLocalProvider(LocalContainer provides c) {
                UltronTheme(s.theme, prefs) {
                    if (loaded != null) AppRoot(s)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Never keep the mic open or keep talking when the app leaves the foreground.
        container.stt.cancel()
        container.tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            container.stt.release()
            container.tts.shutdown()
        }
    }
}
