package com.ultron.ai.domain.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

data class GeoPlace(val name: String, val country: String?, val latitude: Double, val longitude: Double)

data class WeatherReading(
    val city: String,
    val country: String?,
    val tempC: Double,
    val feelsC: Double?,
    val humidity: Int?,
    val windKph: Double?,
    val description: String,
    /** The provider's own observation time (local time at the place), e.g. "2026-10-25T06:30". */
    val observedAt: String?,
)

/**
 * Pure helpers for the official Open-Meteo APIs (https://open-meteo.com/en/docs and /en/docs/geocoding-api).
 * No network here: URL building and response parsing only, so it is unit-testable. Nothing is ever invented:
 * a response that lacks a temperature yields null, never a default value.
 */
object OpenMeteo {
    const val GEOCODING_HOST = "geocoding-api.open-meteo.com"
    const val FORECAST_HOST = "api.open-meteo.com"
    const val ATTRIBUTION = "Weather data by Open-Meteo.com"

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** True for a plausible city query (letters, digits, spaces, a few punctuation marks; 2..80 chars). */
    fun isValidCity(raw: String): Boolean {
        val s = raw.trim()
        return s.length in 2..80 && s.any { it.isLetter() } && s.all { it.isLetterOrDigit() || it in " .,'-()" }
    }

    fun parseGeocode(body: String): GeoPlace? = try {
        val first = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
        val lat = first?.num("latitude"); val lon = first?.num("longitude")
        val name = (first?.get("name") as? JsonPrimitive)?.contentOrNull
        if (first == null || lat == null || lon == null || name.isNullOrBlank() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
        else GeoPlace(name, (first["country"] as? JsonPrimitive)?.contentOrNull, lat, lon)
    } catch (_: Exception) { null }

    fun parseForecast(body: String, place: GeoPlace): WeatherReading? = try {
        val cur = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
        val temp = cur?.num("temperature_2m")
        if (cur == null || temp == null || temp !in -90.0..65.0) null
        else WeatherReading(
            city = place.name, country = place.country, tempC = temp,
            feelsC = cur.num("apparent_temperature"),
            humidity = cur.num("relative_humidity_2m")?.toInt()?.takeIf { it in 0..100 },
            windKph = cur.num("wind_speed_10m")?.takeIf { it >= 0 },
            description = describe(cur.num("weather_code")?.toInt()),
            observedAt = (cur["time"] as? JsonPrimitive)?.contentOrNull,
        )
    } catch (_: Exception) { null }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** WMO weather interpretation codes, as documented by Open-Meteo. */
    fun describe(code: Int?): String = when (code) {
        null -> ""
        0 -> "Saaf aasmaan"
        1 -> "Zyadatar saaf"
        2 -> "Thode baadal"
        3 -> "Badal chhaye"
        45, 48 -> "Kohra"
        51, 53, 55 -> "Halki phuhaar"
        56, 57 -> "Thandi phuhaar"
        61 -> "Halki baarish"
        63 -> "Baarish"
        65 -> "Tez baarish"
        66, 67 -> "Jamne wali baarish"
        71, 73, 75, 77 -> "Barfbaari"
        80, 81, 82 -> "Baarish ki bauchhar"
        85, 86 -> "Barf ki bauchhar"
        95 -> "Aandhi-toofan"
        96, 99 -> "Olon ke saath toofan"
        else -> "Mausam code $code"
    }
}
