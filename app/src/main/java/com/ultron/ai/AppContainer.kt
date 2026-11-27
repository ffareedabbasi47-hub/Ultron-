package com.ultron.ai

import android.app.Application
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.core.diagnostics.LatencyStats
import com.ultron.ai.core.security.SecureStore
import com.ultron.ai.data.ai.AIProvider
import com.ultron.ai.data.ai.GeminiDirectProvider
import com.ultron.ai.data.ai.AssistantEngine
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual dependency container (small app: no DI framework needed). */
class AppContainer(private val app: Application) {
    val diagnostics = DiagnosticsLog()
    val latency = LatencyStats()
    val secure = SecureStore(app)
    val settings = SettingsRepository(app, secure)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init { appScope.launch(Dispatchers.IO) { settings.migrateRemovedBackend() } }

    /** True if the encrypted DB had to be recreated because its Keystore key was lost. */
    @Volatile var databaseWasReset = false
    val db: UltronDatabase = UltronDatabase.open(app, secure) {
        databaseWasReset = true
        diagnostics.log("db", "keystore key lost: local database was recreated")
    }

    /** No logging interceptor is installed: request/response bodies are never logged. */
    val http: OkHttpClient = OkHttpClient.Builder().build()

    val geminiDirect = GeminiDirectProvider(settings, http, diagnostics)
    /** The one online provider: Google Gemini, called directly with the user's own API key. */
    val provider: AIProvider = geminiDirect
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
            screenConsent = { settings.settings.first().let { com.ultron.ai.domain.ConsentPolicy.canSendScreenText(it.cloudConsent, it.screenCloudConsent, settings.geminiKey() != null) } },
        )
    }
    /** Second agent: the voice agent hands coding jobs to it; it saves finished projects to the user's folder. */
    val builder = com.ultron.ai.platform.builder.BuilderAgent(
        app, appScope, settings, oneShot, db.workDocs(), com.ultron.ai.platform.builder.BuilderStore(app), diagnostics,
    ) { job -> notifyBuilder(job) }
    init { actions.builder = builder }

    private fun notifyBuilder(job: com.ultron.ai.platform.builder.BuildJob) {
        val ok = job.stage == com.ultron.ai.platform.builder.BuildStage.DONE
        val line = if (ok) "Builder ne kaam poora kar diya. ${job.files.size} files, ${job.where}." else "Builder fail hua: ${job.message}"
        try {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("builder", "Builder agent", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val pi = android.app.PendingIntent.getActivity(app, 7, android.content.Intent(app, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(9000 + (job.id % 1000).toInt(), androidx.core.app.NotificationCompat.Builder(app, "builder")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (ok) "Project ready" else "Builder fail")
                .setContentText(line).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(line))
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) { /* notification permission may be off: the Builder screen still shows the result */ }
        if (!liveCall.isActive) { val k = tts.beginSpeech(0L); tts.enqueue(k, line); tts.endSpeech(k) }
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

    val turns = TurnController(engine, tts, stt, settings, diagnostics, appScope, assistantState)

    init {
        appScope.launch { geminiDirect.prewarm() }
        // Revoking cloud consent cancels in-flight chat and blocks new requests (providers also check at call time).
        appScope.launch {
            var wasOn: Boolean? = null
            settings.settings.map { it.cloudConsent }.distinctUntilChanged().collect { on ->
                if (wasOn == true && !on) { turns.cancel(); liveStt.close() }
                if (wasOn != true && on) geminiDirect.prewarm()
                wasOn = on
            }
        }
    }

    val mic = com.ultron.ai.platform.live.MicCapture(app, diagnostics)
    val transcriber = com.ultron.ai.platform.live.GeminiTranscriber(settings, http, diagnostics)
    val liveStt = com.ultron.ai.platform.live.LiveTranscriber(settings, http, diagnostics, appScope)
    /** The one live-conversation owner (see LiveSessionEngine). */
    val personalWake: com.ultron.ai.platform.live.PersonalWake = com.ultron.ai.platform.live.PersonalWake(app, mic, diagnostics)
    val onnxWake: com.ultron.ai.platform.live.OnnxWake = com.ultron.ai.platform.live.OnnxWake(app, diagnostics)
    val webSearch: com.ultron.ai.platform.live.WebSearch = com.ultron.ai.platform.live.WebSearch(settings, http, diagnostics)
    /** Real facts about this phone for the Capability Registry. */
    suspend fun capabilityEnv(): com.ultron.ai.domain.CapabilityEnv {
        val s = settings.settings.first()
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(app, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg -> try { app.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false } }
        return com.ultron.ai.domain.CapabilityEnv(
            micPermission = granted(android.Manifest.permission.RECORD_AUDIO), cloudConsent = s.cloudConsent, geminiKey = settings.geminiKey() != null,
            liveCallEnabled = s.liveCall, wakeReady = personalWake.ready(com.ultron.ai.domain.wake.WakePhrase.HEY) || onnxWake.hasModelFiles(),
            contactsPermission = granted(android.Manifest.permission.READ_CONTACTS), callPermission = granted(android.Manifest.permission.CALL_PHONE),
            smsPermission = granted(android.Manifest.permission.SEND_SMS), whatsappInstalled = wa,
            accessibilityOn = screenControl.isEnabled(), telephony = app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY),
        )
    }

    /** Phone-call style conversation (Gemini Live). Local actions requested by the model go through the same validated runner. */
    val liveCall: com.ultron.ai.platform.live.LiveCall = com.ultron.ai.platform.live.LiveCall(
        appScope, settings, http, diagnostics,
        runTool = { name, args ->
            val action: com.ultron.ai.domain.UltronAction? = when (name) {
                "open_app" -> args["name"]?.takeIf { it.isNotBlank() }?.let { com.ultron.ai.domain.UltronAction.OpenApp(it.take(60)) }
                "youtube_search" -> args["query"]?.takeIf { it.isNotBlank() }?.let { com.ultron.ai.domain.UltronAction.YouTubeSearch(it.take(120)) }
                "phone_toggle" -> {
                    val t = when (args["name"]?.lowercase()?.replace(" ", "_")) {
                        "wifi", "wi-fi" -> com.ultron.ai.domain.ToggleTarget.WIFI
                        "hotspot" -> com.ultron.ai.domain.ToggleTarget.HOTSPOT
                        "bluetooth" -> com.ultron.ai.domain.ToggleTarget.BLUETOOTH
                        "airplane" -> com.ultron.ai.domain.ToggleTarget.AIRPLANE
                        "mobile_data" -> com.ultron.ai.domain.ToggleTarget.MOBILE_DATA
                        "location" -> com.ultron.ai.domain.ToggleTarget.LOCATION
                        "nfc" -> com.ultron.ai.domain.ToggleTarget.NFC
                        "torch", "flashlight" -> com.ultron.ai.domain.ToggleTarget.TORCH
                        else -> null
                    }
                    val on = when (args["state"]?.lowercase()) { "on" -> true; "off" -> false; else -> null }
                    if (t != null && on != null) com.ultron.ai.domain.UltronAction.SetToggle(t, on) else null
                }
                "build_project" -> args["brief"]?.takeIf { it.isNotBlank() }?.let { com.ultron.ai.domain.UltronAction.BuildProject(it.take(600)) }
                "set_reminder" -> args["text"]?.takeIf { it.isNotBlank() }?.let {
                    com.ultron.ai.domain.UltronAction.SetReminder(it.take(120), (args["minutes"]?.toIntOrNull() ?: 10).coerceIn(1, 1440))
                }
                else -> null
            }
            if (name == "web_search") {
                val q = args["query"]?.trim().orEmpty()
                if (q.isEmpty()) "No query." else when (val o = webSearch.search(q)) {
                    is com.ultron.ai.platform.live.WebSearch.Outcome.Ok -> {
                        liveChat.addNote(com.ultron.ai.platform.live.WebSearch.format(o.result)) // sources visible in Chat
                        "WEB RESULT (from the internet, tell the user it is from the web): " + o.result.summary + if (o.result.sources.isEmpty()) " [no sources returned: say it is unverified]" else ""
                    }
                    is com.ultron.ai.platform.live.WebSearch.Outcome.Failed -> "SEARCH FAILED: " + o.message + " Tell the user honestly that no web search happened."
                }
            } else if (name == "my_capabilities") {
                val st = com.ultron.ai.domain.CapabilityRegistry.evaluate(capabilityEnv())
                com.ultron.ai.domain.CapabilityRegistry.describe(st, com.ultron.ai.domain.CapabilityRegistry.focusFor(args["topic"].orEmpty()))
            } else if (name == "stop_listening") "ok" else if (action == null) "not supported" else actions.run(action).message
        },
        onMouth = { cloudVoice.pushExternalMouth(it) },
        onFirstAudioMs = { latency.recordFirstAudio(it) },
    )
    val live: com.ultron.ai.platform.live.LiveSessionEngine = com.ultron.ai.platform.live.LiveSessionEngine(app, appScope, settings, mic, transcriber, turns, engine, tts, cloudVoice, diagnostics, broker, personalWake, liveStt, liveCall, com.ultron.ai.voice.AndroidAudioSessionManager(app), com.ultron.ai.platform.live.LocalTranscriber(app), onnxWake)
    val liveChat: com.ultron.ai.platform.live.LiveChat = com.ultron.ai.platform.live.LiveChat(appScope, liveCall, settings, db.conversations(), engine, turns, diagnostics) { live.voiceCallActive }
}

data class PendingPrompt(val text: String, val autoSend: Boolean, val study: Boolean = false)

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFFLINE }
