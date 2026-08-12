package com.voxyn.web

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenMeteoWeatherProvider — keyless weather via Open-Meteo:
 *
 *   geocoding-api.open-meteo.com  (city → lat/lon)
 *   api.open-meteo.com            (daily max/min temp + weather code)
 *
 * Responses are formatted for TTS ("31 degrees" not "31°") and never contain
 * raw JSON. Returns null on any failure so the resolver can fall back.
 */
class OpenMeteoWeatherProvider : WeatherProvider {

    override suspend fun forecast(location: String, dayOffset: Int): WeatherResult? {
        val clean = location.trim().lowercase()
        if (clean.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val coords = geocode(clean) ?: return@runCatching null
                val encodedLat = coords.first
                val encodedLon = coords.second
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$encodedLat&longitude=$encodedLon" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode&forecast_days=3&timezone=auto"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
                }
                if (conn.responseCode != 200) {
                    VoxynLogger.w("Open-Meteo HTTP ${conn.responseCode}", "WEB")
                    return@runCatching null
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val daily = json.optJSONObject("daily") ?: return@runCatching null
                val max = daily.optJSONArray("temperature_2m_max")
                val min = daily.optJSONArray("temperature_2m_min")
                val codes = daily.optJSONArray("weathercode")
                if (max == null || max.length() <= dayOffset) return@runCatching null

                val dayMax = max.optDouble(dayOffset)
                val dayMin = min?.optDouble(dayOffset) ?: dayMax
                val code = codes?.optInt(dayOffset) ?: 0
                val dayWord = WeatherDay.label(dayOffset)
                val pretty = clean.split(" ").joinToString(" ") { w ->
                    w.replaceFirstChar { it.uppercase() }
                }
                val text = if (dayOffset == 0) {
                    "In $pretty today, around ${fmtTemp(dayMax)} with ${describeWeatherCode(code)}."
                } else {
                    "In $pretty $dayWord, up to ${fmtTemp(dayMax)}, low of ${fmtTemp(dayMin)}, ${describeWeatherCode(code)}."
                }
                VoxynLogger.i("[WEB] Weather: $pretty day=$dayOffset", "WEB")
                WeatherResult(text, pretty, dayOffset)
            }.getOrNull()
        }
    }

    private suspend fun geocode(location: String): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(location, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
        }
        if (conn.responseCode != 200) {
            VoxynLogger.w("Open-Meteo geocoding HTTP ${conn.responseCode}", "WEB")
            return null
        }
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        val lat = first.optDouble("latitude")
        val lon = first.optDouble("longitude")
        return if (lat == 0.0 && lon == 0.0) null else lat to lon
    }

    private fun fmtTemp(value: Double): String =
        "${Math.round(value)} degrees"

    /** Maps WMO weather codes to short TTS descriptions. */
    internal fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "clear skies"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "light rain"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "a thunderstorm"
        else -> "mixed weather"
    }
}
