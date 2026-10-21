package com.ultron.ai.data.weather

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.StreamProtocol
import com.ultron.ai.data.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class Weather(
    val city: String,
    val country: String? = null,
    val tempC: Double,
    val feelsC: Double? = null,
    val humidity: Int? = null,
    val windKph: Double? = null,
    val description: String = "",
)

sealed interface WeatherState {
    data object Idle : WeatherState
    data object Loading : WeatherState
    /** Honest "can't show weather because ..." state; nothing is invented. */
    data class Unavailable(val reason: String, val canRetry: Boolean) : WeatherState
    data class Available(val weather: Weather, val fetchedAtMs: Long) : WeatherState
}

@Serializable private data class WeatherErr(val error: String? = null)

/** Real weather via the backend (Open-Meteo). Never fabricates values. */
class WeatherRepository(
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) {
    private val _state = MutableStateFlow<WeatherState>(WeatherState.Idle)
    val state: StateFlow<WeatherState> = _state

    suspend fun refresh() {
        val s = settings.settings.first()
        val base = s.backendUrl.trim().toHttpUrlOrNull()
        if (base == null) {
            _state.value = WeatherState.Unavailable("Backend URL set nahi hai (Menu → Connectivity).", canRetry = false)
            return
        }
        if (s.weatherCity.isBlank()) {
            _state.value = WeatherState.Unavailable("Shehar set nahi hai (Menu → Connectivity → Weather city).", canRetry = false)
            return
        }
        _state.value = WeatherState.Loading
        _state.value = withContext(Dispatchers.IO) {
            try {
                val url = base.newBuilder().addPathSegments("v1/weather").addQueryParameter("city", s.weatherCity.trim()).build()
                val req = Request.Builder().url(url).get().apply {
                    settings.accessToken()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                }.build()
                client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> WeatherState.Available(
                            StreamProtocol.json.decodeFromString(Weather.serializer(), body),
                            System.currentTimeMillis(),
                        )
                        r.code == 404 -> WeatherState.Unavailable("Ye shehar nahi mila. Spelling check karo.", canRetry = false)
                        r.code == 401 -> WeatherState.Unavailable("Access token galat/missing hai.", canRetry = false)
                        r.code == 429 -> WeatherState.Unavailable("Bahut zyada requests. Thodi der baad retry karo.", canRetry = true)
                        else -> {
                            val code = runCatching { StreamProtocol.json.decodeFromString(WeatherErr.serializer(), body).error }.getOrNull()
                            WeatherState.Unavailable("Weather service error (${code ?: r.code}).", canRetry = true)
                        }
                    }
                }
            } catch (e: IOException) {
                diagnostics.log("weather", "io error ${e.javaClass.simpleName}")
                WeatherState.Unavailable("Offline ya backend tak pahunch nahi paya.", canRetry = true)
            } catch (e: Exception) {
                WeatherState.Unavailable("Weather data samajh nahi aaya.", canRetry = true)
            }
        }
    }
}
