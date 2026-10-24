package com.ultron.ai

import android.app.Application
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.core.diagnostics.LatencyStats
import com.ultron.ai.core.security.SecureStore
import com.ultron.ai.data.ai.AIProvider
import com.ultron.ai.data.ai.GeminiDirectProvider
import com.ultron.ai.data.ai.RoutingProvider
import com.ultron.ai.data.ai.AssistantEngine
import com.ultron.ai.data.ai.ProxyProvider
import com.ultron.ai.data.db.UltronDatabase
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.data.weather.WeatherRepository
import com.ultron.ai.domain.IdentityResponder
import com.ultron.ai.domain.SmallTalk
import com.ultron.ai.platform.actions.DeviceActionRunner
import com.ultron.ai.platform.reminders.ReminderScheduler
import com.ultron.ai.platform.vision.OcrEngine
import com.ultron.ai.platform.voice.SttController
import com.ultron.ai.platform.voice.TtsController
import com.ultron.ai.data.ai.TurnController
import com.ultron.ai.data.avatar.AvatarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual dependency container (small app: no DI framework needed). */
class AppContainer(app: Application) {
    val diagnostics = DiagnosticsLog()
    val latency = LatencyStats()
    val secure = SecureStore(app)
    val settings = SettingsRepository(app, secure)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True if the encrypted DB had to be recreated because its Keystore key was lost. */
    @Volatile var databaseWasReset = false
    val db: UltronDatabase = UltronDatabase.open(app, secure) {
        databaseWasReset = true
        diagnostics.log("db", "keystore key lost: local database was recreated")
    }

    /** No logging interceptor is installed: request/response bodies are never logged. */
    val http: OkHttpClient = OkHttpClient.Builder().build()

    private val proxyProvider = ProxyProvider(settings, http, diagnostics)
    val geminiDirect = GeminiDirectProvider(settings, http, diagnostics)
    /** Uses the user's own Gemini key when saved (fastest, one hop), else the ULTRON backend proxy. */
    val provider: AIProvider = RoutingProvider(settings, proxyProvider, geminiDirect)
    val cloudVoice = com.ultron.ai.platform.voice.CloudVoice(settings, http, diagnostics, appScope)
    val tts = TtsController(app, diagnostics, latency, cloudVoice)
    val stt = SttController(app, diagnostics)
    val reminders = ReminderScheduler(app, db.reminders())
    val screenControl: com.ultron.ai.domain.ScreenControl = com.ultron.ai.platform.accessibility.AccessibilityScreenControl()
    val broker = com.ultron.ai.platform.actions.ConfirmationBroker()
    val actions = DeviceActionRunner(app, db.memory(), reminders, tts, screenControl, broker)
    val identity = IdentityResponder(BuildConfig.CREATOR_NAME)
    val engine = AssistantEngine(db.conversations(), provider, settings, identity, SmallTalk(BuildConfig.CREATOR_NAME), actions, latency, diagnostics)
    val oneShot = com.ultron.ai.data.ai.GeminiOneShot(settings, http, diagnostics)
    init {
        actions.agent = com.ultron.ai.platform.actions.ScreenAgent(
            screenControl, oneShot, broker,
            openApp = { name -> actions.run(com.ultron.ai.domain.UltronAction.OpenApp(name)) },
            speak = { t -> val k = tts.beginSpeech(0L); tts.enqueue(k, t); tts.endSpeech(k) },
            diagnostics = diagnostics,
        )
    }
    val weather = WeatherRepository(settings, http, diagnostics)
    val ocr = OcrEngine(app)
    val avatars = AvatarRepository(app, http, diagnostics)

    /** Text handed from another screen (Home composer, Scan) to the Chat screen. */
    val pendingPrompt = MutableStateFlow<PendingPrompt?>(null)
    /** Set by the dock mic; Home consumes it and toggles listening there. */
    val micRequest = MutableStateFlow(false)
    /** Set by the assistant gesture / ASSIST intent: start live (never toggles it off). */
    val liveStartRequest = MutableStateFlow(false)
    /** Incremented when Home is tapped while already on Home: scroll to top. */
    val homeReselect = MutableStateFlow(0)

    /** Real, app-wide assistant state shown by the core animation on every screen. */
    val assistantState = MutableStateFlow(AssistantState.IDLE)

    init { appScope.launch { geminiDirect.prewarm() } }

    val turns = TurnController(engine, tts, stt, settings, diagnostics, appScope, assistantState)

    val mic = com.ultron.ai.platform.live.MicCapture(app, diagnostics)
    val transcriber = com.ultron.ai.platform.live.GeminiTranscriber(settings, http, diagnostics)
    /** The one live-conversation owner (see LiveSessionEngine). */
    val live = com.ultron.ai.platform.live.LiveSessionEngine(app, appScope, settings, mic, transcriber, turns, engine, tts, cloudVoice, diagnostics, broker)
}

data class PendingPrompt(val text: String, val autoSend: Boolean, val study: Boolean = false)

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFFLINE }
