package com.ultron.ai.data.weather

import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.weather.GeoPlace
import com.ultron.ai.domain.weather.OpenMeteo
import com.ultron.ai.domain.weather.WeatherReading
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class Weather(
    val city: String,
    val country: String? = null,
    val tempC: Double,
    val feelsC: Double? = null,
    val humidity: Int? = null,
    val windKph: Double? = null,
    val description: String = "",
    val observedAt: String? = null,
)

sealed interface WeatherState {
    data object Idle : WeatherState
    data object Loading : WeatherState
    /** Honest "can't show weather because ..." state; nothing is invented. */
    data class Unavailable(val reason: String, val canRetry: Boolean) : WeatherState
    /**
     * [stale] = the last refresh failed and this is the previous successful reading ([staleReason] says why).
     * [fetchedAtMs] is when THIS data was fetched, never the time of a failed attempt.
     */
    data class Available(val weather: Weather, val fetchedAtMs: Long, val stale: Boolean = false, val staleReason: String? = null) : WeatherState
}

/**
 * Weather straight from the official Open-Meteo geocoding + forecast APIs (no backend, no API key, no location
 * permission: the user types a city). Never routed through Gemini. Never fabricates values: if a fetch fails the
 * previous reading is shown clearly marked stale, or an unavailable state if there is none.
 */
class WeatherRepository(
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) {
    private val _state = MutableStateFlow<WeatherState>(WeatherState.Idle)
    val state: StateFlow<WeatherState> = _state
    private val lock = Mutex()
    private val places = HashMap<String, GeoPlace>()
    private var last: WeatherState.Available? = null
    private var lastCity: String? = null

    private fun http() = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    private fun fail(reason: String, canRetry: Boolean, city: String): WeatherState {
        val prev = last
        return if (prev != null && lastCity == city) prev.copy(stale = true, staleReason = reason) else WeatherState.Unavailable(reason, canRetry)
    }

    suspend fun refresh() = lock.withLock {
        val city = settings.settings.first().weatherCity.trim()
        if (city.isEmpty()) { _state.value = WeatherState.Unavailable("Shehar set nahi hai. Mausam screen mein shehar likho.", canRetry = false); return@withLock }
        if (!OpenMeteo.isValidCity(city)) { _state.value = WeatherState.Unavailable("Shehar ka naam sahi nahi lag raha.", canRetry = false); return@withLock }
        if (lastCity != city) { last = null }
        if (last == null) _state.value = WeatherState.Loading
        _state.value = withContext(Dispatchers.IO) {
            try {
                val key = city.lowercase()
                val place = places[key] ?: run {
                    val url = HttpUrl.Builder().scheme("https").host(OpenMeteo.GEOCODING_HOST).addPathSegments("v1/search")
                        .addQueryParameter("name", city).addQueryParameter("count", "1").addQueryParameter("language", "en").addQueryParameter("format", "json").build()
                    val body = get(url) ?: return@withContext fail("Mausam service ne jawab nahi diya.", true, city)
                    OpenMeteo.parseGeocode(body)?.also { places[key] = it }
                        ?: return@withContext WeatherState.Unavailable("Ye shehar nahi mila. Spelling check karo.", canRetry = false)
                }
                val url = HttpUrl.Builder().scheme("https").host(OpenMeteo.FORECAST_HOST).addPathSegments("v1/forecast")
                    .addQueryParameter("latitude", place.latitude.toString()).addQueryParameter("longitude", place.longitude.toString())
                    .addQueryParameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
                    .addQueryParameter("timezone", "auto").build()
                val body = get(url) ?: return@withContext fail("Mausam service ne jawab nahi diya.", true, city)
                val r: WeatherReading = OpenMeteo.parseForecast(body, place)
                    ?: return@withContext fail("Mausam data samajh nahi aaya.", true, city)
                WeatherState.Available(
                    Weather(r.city, r.country, r.tempC, r.feelsC, r.humidity, r.windKph, r.description, r.observedAt), System.currentTimeMillis(),
                ).also { last = it; lastCity = city }
            } catch (e: SocketTimeoutException) {
                diagnostics.log("weather", "timeout")
                fail("Mausam service ne time par jawab nahi diya.", true, city)
            } catch (e: IOException) {
                diagnostics.log("weather", "io ${e.javaClass.simpleName}")
                fail("Internet nahi hai ya mausam service tak pahunch nahi paya.", true, city)
            } catch (e: Exception) {
                diagnostics.log("weather", "error ${e.javaClass.simpleName}")
                fail("Mausam data samajh nahi aaya.", true, city)
            }
        }
    }

    /** Returns the body for HTTP 200, null otherwise (429/5xx are reported as "no answer"; the user can retry). */
    private fun get(url: HttpUrl): String? =
        http().newCall(Request.Builder().url(url).get().build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
}
