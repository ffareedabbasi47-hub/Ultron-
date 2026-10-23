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
    val selectedModel: String? = null,
    /** Model used when the user's own Gemini key is saved (direct route). */
    val geminiModel: String = "gemini-3.5-flash-lite",
    /** Standing user preferences appended to the system prompt ("ULTRON ke rules"). */
    val customRules: String = "",
    val cloudConsent: Boolean = false,

    val streaming: Boolean = true,
    val timeoutSec: Int = 30,
    val maxRetries: Int = 2,

    val backendUrl: String = BuildConfig.BACKEND_URL,
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

    /** Access token for the backend proxy; stored encrypted by Android Keystore. */
    fun accessToken(): String? = secure.getString(TOKEN_NAME)
    fun setAccessToken(token: String?) = secure.putString(TOKEN_NAME, token?.trim())

    /** The user's own Gemini API key (encrypted with the Keystore). Never logged, never in the APK. */
    fun geminiKey(): String? = secure.getString(GEMINI_KEY_NAME)?.takeIf { it.isNotBlank() }
    fun setGeminiKey(key: String?) = secure.putString(GEMINI_KEY_NAME, key?.trim())

    suspend fun resetAll() {
        store.edit { it.clear() }
        secure.putString(TOKEN_NAME, null)
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
            selectedModel = this[K_MODEL],
            geminiModel = this[K_GEMINI_MODEL] ?: d.geminiModel,
            customRules = this[K_RULES] ?: d.customRules,
            cloudConsent = this[K_CONSENT] ?: d.cloudConsent,
            streaming = this[K_STREAM] ?: d.streaming,
            timeoutSec = this[K_TIMEOUT] ?: d.timeoutSec,
            maxRetries = this[K_RETRIES] ?: d.maxRetries,
            backendUrl = this[K_BACKEND] ?: d.backendUrl,
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
        setOrRemove(K_MODEL, s.selectedModel)
        this[K_GEMINI_MODEL] = s.geminiModel
        this[K_RULES] = s.customRules
        this[K_CONSENT] = s.cloudConsent
        this[K_STREAM] = s.streaming
        this[K_TIMEOUT] = s.timeoutSec
        this[K_RETRIES] = s.maxRetries
        this[K_BACKEND] = s.backendUrl
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
        const val TOKEN_NAME = "backend_access_token"
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
        val K_MODEL = stringPreferencesKey("selected_model")
        val K_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val K_RULES = stringPreferencesKey("custom_rules")
        val K_CONSENT = booleanPreferencesKey("cloud_consent")
        val K_STREAM = booleanPreferencesKey("streaming")
        val K_TIMEOUT = intPreferencesKey("timeout_sec")
        val K_RETRIES = intPreferencesKey("max_retries")
        val K_BACKEND = stringPreferencesKey("backend_url")
        val K_CITY = stringPreferencesKey("weather_city")
    }
}
