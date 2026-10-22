package com.voxyn.web

/**
 * WeatherProvider — current/forecast weather behind an interface so the hybrid
 * resolver never depends on a specific service.
 */
data class WeatherResult(
    val text: String,
    val location: String,
    val dayOffset: Int
)

interface WeatherProvider {
    /** [dayOffset] is 0 = today, 1 = tomorrow, 2 = day after tomorrow. */
    suspend fun forecast(location: String, dayOffset: Int): WeatherResult?
}

object WeatherDay {
    /** Maps spoken day words to a forecast offset; null when not a day word. */
    fun offsetFrom(text: String): Int? = when (text.trim().lowercase()) {
        "today", "now", "tonight" -> 0
        "tomorrow", "tmrw" -> 1
        "day after tomorrow", "the day after tomorrow", "day after", "the day after" -> 2
        else -> null
    }

    /** TTS-friendly description of an offset. */
    fun label(offset: Int): String = when (offset) {
        0 -> "today"
        1 -> "tomorrow"
        2 -> "the day after tomorrow"
        else -> "in a few days"
    }
}
