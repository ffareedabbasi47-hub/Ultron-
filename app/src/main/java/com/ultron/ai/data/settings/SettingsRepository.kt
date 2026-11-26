package com.ultron.ai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ultron.ai.BuildConfig
import com.ultron.ai.core.security.SecureStore
import com.ultron.ai.domain.live.SleepTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppLanguage(val wire: String, val label: String, val speechTag: String) {
    HINGLISH("hinglish", "Roman Hinglish", "en-IN"),
    HINDI("hindi", "हिन्दी", "hi-IN"),
    ENGLISH("english", "English", "en-IN"),
}

enum class ResponseStyle(val wire: String, val label: String) {
    SHORT("short", "Short & sharp"),
    DETAILED("detailed", "Detailed"),
}

enum class ThemeVariant(val label: String) {
    NAVY("Midnight navy"), PURPLE("Deep purple"), CRIMSON("Ultron red"), EMERALD("Emerald"), GOLD("Gold"), ICE("Ice blue"), AMOLED("AMOLED black"),
}

/** System font families only (no downloads, no licensing issues). */
enum class AppFont(val label: String) {
    DEFAULT("Sans (default)"), CONDENSED("Condensed (sci-fi)"), SERIF("Serif"), MONO("Monospace"), CASUAL("Casual"), CURSIVE("Cursive"),
}

data class UltronSettings(
    val userName: String = BuildConfig.CREATOR_NAME,
    val language: AppLanguage = AppLanguage.HINGLISH,
    val theme: ThemeVariant = ThemeVariant.NAVY,
    val font: AppFont = AppFont.DEFAULT,
    /** Text size multiplier on top of the system font scale. */
    val textScale: Float = 1f,
    val reducedMotion: Boolean = false,
    val lowPerformance: Boolean = false,
    /** "core" (2D animated core), "ultron3d" (built-in 3D avatar) or "custom:<file>.glb". */
    val avatarId: String = "ultron3d",

    val ttsEngine: String? = null,
    val ttsVoice: String? = null,
    val ttsRate: Float = 0.86f,
    val ttsPitch: Float = 0.66f,
    val ttsVolume: Float = 1f,
    val speakReplies: Boolean = true,

    val responseStyle: ResponseStyle = ResponseStyle.SHORT,
    val historyTurns: Int = 8,
    /** Model used when the user's own Gemini key is saved (direct route). */
    val geminiModel: String = "gemini-3.5-flash-lite",
    /** Standing user preferences appended to the system prompt ("ULTRON ke rules"). */
    val customRules: String = "",
    val cloudConsent: Boolean = false,
    /** Offline menacing-machine treatment on the phone's voice, and synthetic breaths between sentences. */
    val voiceFx: Boolean = false,
    val voiceBreath: Boolean = false,
    /** Live voice: stream speech to Gemini while talking (fast). Off = upload each finished sentence (slower). */
    val streamStt: Boolean = true,
    /** Phone-call style live conversation over the Gemini Live API (all mic audio streams to Google while on). */
    val liveCall: Boolean = true,
    /** Folder (SAF tree URI) where the Builder agent saves finished projects. Empty = app-private folder. */
    val builderFolderUri: String = "",
    /** Blocks screenshots/screen recording and hides ULTRON in the recent-apps preview (chats, journal, API key). */
    val secureScreen: Boolean = true,
    /** Separate, explicit permission for sending on-screen text (accessibility tree) to Gemini in screen-agent mode. */
    val screenCloudConsent: Boolean = false,

    val streaming: Boolean = true,
    val timeoutSec: Int = 30,
    val maxRetries: Int = 2,

    // ---- Live conversation
    /** "Chup rehne par Live Session Sleep". */
    val sleepTimeout: SleepTimeout = SleepTimeout.M10,
    /** Pause (ms) after which an utterance is considered finished. */
    val endPauseMs: Int = 450,
    /**
     * true: keep listening while ULTRON speaks and allow barge-in (relies on the device's acoustic echo canceller).
     * false (default, safest): speech that starts while ULTRON talks is ignored; the recorder stays open.
     */
    val fullDuplex: Boolean = false,
    /** Slightly stricter speech detection so video/music in the background is less likely to count as you. */
    val reduceMediaInterference: Boolean = true,
    /** While asleep, check short utterances for "Hey Ultron" by sending them to Gemini (cloud). Off by default. */
    val wakeCloudCheck: Boolean = false,
    /** Offline personal wake word (needs the user's own recordings). Free: no network, no quota. */
    val wakePersonal: Boolean = true,
    val wakeSensitivity: Float = 1f,
    /** Start armed: the mic only feeds the offline wake matcher until a wake phrase is heard (nothing is uploaded before). */
    val wakeFirst: Boolean = true,
    /** Rotating light around the app edge while live. */
    val edgeLight: Boolean = true,
    /** The same light over other apps (needs "display over other apps" permission). */
    val overlayLight: Boolean = true,

    // ---- Voice (Gemini online voices)
    /** Default OFF: Gemini TTS models have very small free quotas. The phone voice is free and is lip-synced too. */
    val cloudVoice: Boolean = true,
    /** Direction sent with every online line, e.g. "Say in a deep calm voice:". */
    val voiceStyle: String = "Say the following in a deep, slow, calm, controlled and slightly menacing voice, like a composed machine intelligence. Clear pronunciation, deliberate pauses, never shouting:",
    /** Speak through ULTRON's own player (real lip-sync, echo reference). Off = direct device TTS (no lip-sync). */
    val lipSyncVoice: Boolean = true,
    val cloudVoiceName: String = "Algenib",
    val cloudTtsModel: String = "gemini-2.5-flash-preview-tts",

    val weatherCity: String = "",
)

private val Context.dataStore by preferencesDataStore(name = "ultron_settings")

class SettingsRepository(context: Context, private val secure: SecureStore) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<UltronSettings> = store.data.map { it.toSettings() }

    suspend fun update(block: (UltronSettings) -> UltronSettings) {
        store.edit { prefs ->
            val updated = block(prefs.toSettings())
            prefs.write(updated)
        }
    }

    /**
     * One-time, idempotent cleanup of settings that belonged to the removed backend proxy (URL, selected backend model,
     * access token). Touches nothing else: other settings, the Gemini key and the database are preserved.
     */
    suspend fun migrateRemovedBackend() {
        store.edit { it.remove(K_BACKEND_OLD); it.remove(K_MODEL_OLD) }
        if (secure.getString(OLD_TOKEN_NAME) != null) secure.putString(OLD_TOKEN_NAME, null)
    }

    /** The user's own Gemini API key (encrypted with the Keystore). Never logged, never in the APK. */
    fun geminiKey(): String? = secure.getString(GEMINI_KEY_NAME)?.takeIf { it.isNotBlank() }
    fun setGeminiKey(key: String?) = secure.putString(GEMINI_KEY_NAME, key?.trim())

    suspend fun resetAll() {
        store.edit { it.clear() }
        secure.putString(OLD_TOKEN_NAME, null)
        secure.putString(GEMINI_KEY_NAME, null)
    }

    private fun Preferences.toSettings(): UltronSettings {
        val d = UltronSettings()
        return UltronSettings(
            userName = this[K_NAME] ?: d.userName,
            language = enumOr(this[K_LANG], d.language),
            theme = enumOr(this[K_THEME], d.theme),
            font = enumOr(this[K_FONT], d.font),
            textScale = (this[K_TEXT_SCALE] ?: d.textScale).coerceIn(0.85f, 1.35f),
            reducedMotion = this[K_REDUCED] ?: d.reducedMotion,
            lowPerformance = this[K_LOWPERF] ?: d.lowPerformance,
            avatarId = this[K_AVATAR] ?: d.avatarId,
            ttsEngine = this[K_TTS_ENGINE],
            ttsVoice = this[K_TTS_VOICE],
            ttsRate = this[K_TTS_RATE] ?: d.ttsRate,
            ttsPitch = this[K_TTS_PITCH] ?: d.ttsPitch,
            ttsVolume = this[K_TTS_VOL] ?: d.ttsVolume,
            speakReplies = this[K_SPEAK] ?: d.speakReplies,
            responseStyle = enumOr(this[K_STYLE], d.responseStyle),
            historyTurns = this[K_HISTORY] ?: d.historyTurns,
            geminiModel = this[K_GEMINI_MODEL] ?: d.geminiModel,
            customRules = this[K_RULES] ?: d.customRules,
            cloudConsent = this[K_CONSENT] ?: d.cloudConsent,
            voiceFx = this[K_VFX] ?: d.voiceFx,
            voiceBreath = this[K_VBREATH] ?: d.voiceBreath,
            streamStt = this[K_STREAMSTT] ?: d.streamStt,
            liveCall = this[K_LIVECALL] ?: d.liveCall,
            builderFolderUri = this[K_BUILDER_DIR] ?: d.builderFolderUri,
            secureScreen = this[K_SECURE] ?: d.secureScreen,
            screenCloudConsent = this[K_SCREEN_CONSENT] ?: d.screenCloudConsent,
            streaming = this[K_STREAM] ?: d.streaming,
            timeoutSec = this[K_TIMEOUT] ?: d.timeoutSec,
            maxRetries = this[K_RETRIES] ?: d.maxRetries,
            sleepTimeout = enumOr(this[K_SLEEP], d.sleepTimeout),
            endPauseMs = (this[K_ENDPAUSE] ?: d.endPauseMs).coerceIn(400, 2000),
            fullDuplex = this[K_FULLDUPLEX] ?: d.fullDuplex,
            reduceMediaInterference = this[K_MEDIA] ?: d.reduceMediaInterference,
            wakeCloudCheck = this[K_WAKECLOUD] ?: d.wakeCloudCheck,
            wakePersonal = this[K_WAKEPERS] ?: d.wakePersonal,
            wakeSensitivity = (this[K_WAKESENS] ?: d.wakeSensitivity).coerceIn(0.5f, 2f),
            wakeFirst = this[K_WAKEFIRST] ?: d.wakeFirst,
            edgeLight = this[K_EDGE] ?: d.edgeLight,
            overlayLight = this[K_OVERLAY] ?: d.overlayLight,
            cloudVoice = this[K_CLOUDVOICE] ?: d.cloudVoice,
            voiceStyle = this[K_VSTYLE] ?: d.voiceStyle,
            lipSyncVoice = this[K_LIPSYNC] ?: d.lipSyncVoice,
            cloudVoiceName = this[K_CVNAME] ?: d.cloudVoiceName,
            cloudTtsModel = this[K_CTTSMODEL] ?: d.cloudTtsModel,
            weatherCity = this[K_CITY] ?: d.weatherCity,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: UltronSettings) {
        this[K_NAME] = s.userName
        this[K_LANG] = s.language.name
        this[K_THEME] = s.theme.name
        this[K_FONT] = s.font.name
        this[K_TEXT_SCALE] = s.textScale
        this[K_REDUCED] = s.reducedMotion
        this[K_LOWPERF] = s.lowPerformance
        this[K_AVATAR] = s.avatarId
        setOrRemove(K_TTS_ENGINE, s.ttsEngine)
        setOrRemove(K_TTS_VOICE, s.ttsVoice)
        this[K_TTS_RATE] = s.ttsRate
        this[K_TTS_PITCH] = s.ttsPitch
        this[K_TTS_VOL] = s.ttsVolume
        this[K_SPEAK] = s.speakReplies
        this[K_STYLE] = s.responseStyle.name
        this[K_HISTORY] = s.historyTurns
        this[K_GEMINI_MODEL] = s.geminiModel
        this[K_RULES] = s.customRules
        this[K_CONSENT] = s.cloudConsent
        this[K_VFX] = s.voiceFx
        this[K_VBREATH] = s.voiceBreath
        this[K_STREAMSTT] = s.streamStt
        this[K_LIVECALL] = s.liveCall
        this[K_BUILDER_DIR] = s.builderFolderUri
        this[K_SECURE] = s.secureScreen
        this[K_SCREEN_CONSENT] = s.screenCloudConsent
        this[K_STREAM] = s.streaming
        this[K_TIMEOUT] = s.timeoutSec
        this[K_RETRIES] = s.maxRetries
        this[K_SLEEP] = s.sleepTimeout.name
        this[K_ENDPAUSE] = s.endPauseMs
        this[K_FULLDUPLEX] = s.fullDuplex
        this[K_MEDIA] = s.reduceMediaInterference
        this[K_WAKECLOUD] = s.wakeCloudCheck
        this[K_WAKEPERS] = s.wakePersonal
        this[K_WAKESENS] = s.wakeSensitivity
        this[K_WAKEFIRST] = s.wakeFirst
        this[K_EDGE] = s.edgeLight
        this[K_OVERLAY] = s.overlayLight
        this[K_CLOUDVOICE] = s.cloudVoice
        this[K_VSTYLE] = s.voiceStyle
        this[K_LIPSYNC] = s.lipSyncVoice
        this[K_CVNAME] = s.cloudVoiceName
        this[K_CTTSMODEL] = s.cloudTtsModel
        this[K_CITY] = s.weatherCity
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val OLD_TOKEN_NAME = "backend_access_token" // removed feature; only ever deleted
        const val GEMINI_KEY_NAME = "gemini_api_key"
        val K_NAME = stringPreferencesKey("user_name")
        val K_LANG = stringPreferencesKey("language")
        val K_THEME = stringPreferencesKey("theme")
        val K_FONT = stringPreferencesKey("font")
        val K_TEXT_SCALE = floatPreferencesKey("text_scale")
        val K_REDUCED = booleanPreferencesKey("reduced_motion")
        val K_LOWPERF = booleanPreferencesKey("low_performance")
        val K_AVATAR = stringPreferencesKey("avatar_id")
        val K_TTS_ENGINE = stringPreferencesKey("tts_engine")
        val K_TTS_VOICE = stringPreferencesKey("tts_voice")
        val K_TTS_RATE = floatPreferencesKey("tts_rate")
        val K_TTS_PITCH = floatPreferencesKey("tts_pitch")
        val K_TTS_VOL = floatPreferencesKey("tts_volume")
        val K_SPEAK = booleanPreferencesKey("speak_replies")
        val K_STYLE = stringPreferencesKey("response_style")
        val K_HISTORY = intPreferencesKey("history_turns")
        val K_MODEL_OLD = stringPreferencesKey("selected_model") // removed feature; only ever deleted
        val K_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val K_RULES = stringPreferencesKey("custom_rules")
        val K_CONSENT = booleanPreferencesKey("cloud_consent")
        val K_VFX = booleanPreferencesKey("voice_fx")
        val K_VBREATH = booleanPreferencesKey("voice_breath")
        val K_STREAMSTT = booleanPreferencesKey("stream_stt")
        val K_LIVECALL = booleanPreferencesKey("live_call")
        val K_BUILDER_DIR = stringPreferencesKey("builder_dir")
        val K_SECURE = booleanPreferencesKey("secure_screen")
        val K_SCREEN_CONSENT = booleanPreferencesKey("screen_cloud_consent")
        val K_STREAM = booleanPreferencesKey("streaming")
        val K_TIMEOUT = intPreferencesKey("timeout_sec")
        val K_RETRIES = intPreferencesKey("max_retries")
        val K_SLEEP = stringPreferencesKey("sleep_timeout")
        val K_ENDPAUSE = intPreferencesKey("end_pause_ms")
        val K_FULLDUPLEX = booleanPreferencesKey("full_duplex")
        val K_MEDIA = booleanPreferencesKey("reduce_media")
        val K_WAKECLOUD = booleanPreferencesKey("wake_cloud")
        val K_WAKEPERS = booleanPreferencesKey("wake_personal")
        val K_WAKESENS = floatPreferencesKey("wake_sensitivity")
        val K_WAKEFIRST = booleanPreferencesKey("wake_first")
        val K_EDGE = booleanPreferencesKey("edge_light")
        val K_OVERLAY = booleanPreferencesKey("overlay_light")
        val K_VSTYLE = stringPreferencesKey("voice_style")
        val K_LIPSYNC = booleanPreferencesKey("lipsync_voice")
        val K_CLOUDVOICE = booleanPreferencesKey("cloud_voice")
        val K_CVNAME = stringPreferencesKey("cloud_voice_name")
        val K_CTTSMODEL = stringPreferencesKey("cloud_tts_model")
        val K_BACKEND_OLD = stringPreferencesKey("backend_url") // removed feature; only ever deleted
        val K_CITY = stringPreferencesKey("weather_city")
    }
}
