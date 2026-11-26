package com.ultron.ai.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {
    private val place = GeoPlace("Mumbai", "India", 19.07, 72.88)

    @Test fun geocodeParsesFirstResult() {
        val p = OpenMeteo.parseGeocode("""{"results":[{"id":1,"name":"Mumbai","latitude":19.07283,"longitude":72.88261,"country":"India","admin1":"Maharashtra"}],"generationtime_ms":0.9}""")
        assertNotNull(p)
        assertEquals("Mumbai", p!!.name); assertEquals("India", p.country); assertEquals(19.07283, p.latitude, 1e-9)
    }

    @Test fun geocodeNoResultsOrGarbageIsNull() {
        assertNull(OpenMeteo.parseGeocode("""{"generationtime_ms":0.5}"""))
        assertNull(OpenMeteo.parseGeocode("""{"results":[]}"""))
        assertNull(OpenMeteo.parseGeocode("not json"))
        assertNull(OpenMeteo.parseGeocode("""{"results":[{"name":"X","latitude":123.0,"longitude":10.0}]}"""))
        assertNull(OpenMeteo.parseGeocode("""{"results":[{"latitude":10.0,"longitude":10.0}]}"""))
    }

    @Test fun forecastParsesCurrentValues() {
        val r = OpenMeteo.parseForecast(
            """{"latitude":19.0,"current_units":{"temperature_2m":"°C"},"current":{"time":"2026-10-25T06:30","interval":900,"temperature_2m":27.4,"relative_humidity_2m":71,"apparent_temperature":30.1,"weather_code":2,"wind_speed_10m":11.6}}""", place,
        )
        assertNotNull(r)
        assertEquals(27.4, r!!.tempC, 1e-9); assertEquals(30.1, r.feelsC!!, 1e-9); assertEquals(71, r.humidity)
        assertEquals(11.6, r.windKph!!, 1e-9); assertEquals("Thode baadal", r.description); assertEquals("2026-10-25T06:30", r.observedAt)
        assertEquals("Mumbai", r.city)
    }

    @Test fun forecastWithoutTemperatureIsNullNeverInvented() {
        assertNull(OpenMeteo.parseForecast("""{"current":{"time":"x","weather_code":1}}""", place))
        assertNull(OpenMeteo.parseForecast("""{"error":true,"reason":"bad"}""", place))
        assertNull(OpenMeteo.parseForecast("""{"current":{"temperature_2m":500}}""", place))
        assertNull(OpenMeteo.parseForecast("", place))
    }

    @Test fun optionalFieldsMayBeMissing() {
        val r = OpenMeteo.parseForecast("""{"current":{"temperature_2m":12.0}}""", place)!!
        assertNull(r.feelsC); assertNull(r.humidity); assertNull(r.windKph); assertEquals("", r.description)
    }

    @Test fun cityValidation() {
        assertTrue(OpenMeteo.isValidCity("Mumbai")); assertTrue(OpenMeteo.isValidCity("  New Delhi ")); assertTrue(OpenMeteo.isValidCity("São Paulo"))
        assertFalse(OpenMeteo.isValidCity("")); assertFalse(OpenMeteo.isValidCity("a")); assertFalse(OpenMeteo.isValidCity("12345"))
        assertFalse(OpenMeteo.isValidCity("Mumbai; DROP TABLE")); assertFalse(OpenMeteo.isValidCity("x".repeat(81)))
    }

    @Test fun weatherCodeText() {
        assertEquals("Saaf aasmaan", OpenMeteo.describe(0)); assertEquals("Baarish", OpenMeteo.describe(63))
        assertEquals("Aandhi-toofan", OpenMeteo.describe(95)); assertEquals("", OpenMeteo.describe(null))
        assertTrue(OpenMeteo.describe(1234).contains("1234"))
    }

    @Test fun attributionIsStated() { assertTrue(OpenMeteo.ATTRIBUTION.contains("Open-Meteo")) }
}
