package com.voxyn.ai

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WorldTimeProvider — deterministic, fully offline "what time is it in X?".
 * Maps common city names to IANA time zones. Multi-zone countries ("the US",
 * "Canada") return every major zone explicitly instead of inventing one time —
 * the caller decides, and VOXYN never silently claims a single "US time".
 */
class WorldTimeProvider {

    private val cities = mapOf(
        "new york" to "America/New_York", "nyc" to "America/New_York",
        "washington" to "America/New_York", "boston" to "America/New_York", "miami" to "America/New_York",
        "los angeles" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles",
        "seattle" to "America/Los_Angeles",
        "chicago" to "America/Chicago", "houston" to "America/Chicago", "dallas" to "America/Chicago",
        "denver" to "America/Denver", "phoenix" to "America/Phoenix",
        "toronto" to "America/Toronto", "ottawa" to "America/Toronto", "montreal" to "America/Toronto",
        "vancouver" to "America/Vancouver",
        "mexico city" to "America/Mexico_City",
        "sao paulo" to "America/Sao_Paulo", "rio de janeiro" to "America/Sao_Paulo",
        "buenos aires" to "America/Argentina/Buenos_Aires",
        "london" to "Europe/London",
        "paris" to "Europe/Paris", "berlin" to "Europe/Berlin", "rome" to "Europe/Rome",
        "madrid" to "Europe/Madrid", "barcelona" to "Europe/Madrid", "amsterdam" to "Europe/Amsterdam",
        "zurich" to "Europe/Zurich", "vienna" to "Europe/Vienna", "brussels" to "Europe/Brussels",
        "stockholm" to "Europe/Stockholm", "oslo" to "Europe/Oslo", "copenhagen" to "Europe/Copenhagen",
        "warsaw" to "Europe/Warsaw", "athens" to "Europe/Athens", "lisbon" to "Europe/Lisbon",
        "moscow" to "Europe/Moscow", "vladivostok" to "Asia/Vladivostok", "istanbul" to "Europe/Istanbul",
        "dubai" to "Asia/Dubai", "doha" to "Asia/Qatar", "riyadh" to "Asia/Riyadh",
        "cairo" to "Africa/Cairo", "lagos" to "Africa/Lagos", "nairobi" to "Africa/Nairobi",
        "johannesburg" to "Africa/Johannesburg", "casablanca" to "Africa/Casablanca",
        "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata",
        "kolkata" to "Asia/Kolkata", "bengaluru" to "Asia/Kolkata", "chennai" to "Asia/Kolkata",
        "hyderabad" to "Asia/Kolkata", "india" to "Asia/Kolkata",
        "tokyo" to "Asia/Tokyo", "osaka" to "Asia/Tokyo",
        "beijing" to "Asia/Shanghai", "shanghai" to "Asia/Shanghai", "hong kong" to "Asia/Hong_Kong",
        "seoul" to "Asia/Seoul", "singapore" to "Asia/Singapore", "kuala lumpur" to "Asia/Kuala_Lumpur",
        "bangkok" to "Asia/Bangkok", "jakarta" to "Asia/Jakarta", "manila" to "Asia/Manila",
        "sydney" to "Australia/Sydney", "melbourne" to "Australia/Melbourne",
        "brisbane" to "Australia/Brisbane", "perth" to "Australia/Perth", "adelaide" to "Australia/Adelaide",
        "auckland" to "Pacific/Auckland", "wellington" to "Pacific/Auckland"
    )

    /** Countries with multiple time zones — returned as explicit zone lists. */
    private val countries = mapOf(
        "us" to listOf("new york", "chicago", "denver", "los angeles"),
        "usa" to listOf("new york", "chicago", "denver", "los angeles"),
        "the us" to listOf("new york", "chicago", "denver", "los angeles"),
        "the usa" to listOf("new york", "chicago", "denver", "los angeles"),
        "united states" to listOf("new york", "chicago", "denver", "los angeles"),
        "the united states" to listOf("new york", "chicago", "denver", "los angeles"),
        "america" to listOf("new york", "chicago", "denver", "los angeles"),
        "canada" to listOf("toronto", "vancouver"),
        "australia" to listOf("sydney", "perth"),
        "brazil" to listOf("sao paulo", "rio de janeiro"),
        "russia" to listOf("moscow", "vladivostok")
    )

    fun timeIn(location: String): String? {
        val key = location.trim().lowercase().replace(Regex("\\s+"), " ")
        if (key.isBlank()) return null

        countries[key]?.let { zones ->
            val parts = zones.mapNotNull { city -> cities[city]?.let { zone -> formatZone(city, zone) } }
            if (parts.size >= 2) {
                val joined = parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
                return "${keyName(key)} spans multiple time zones. $joined."
            }
        }

        val zoneId = cities[key] ?: return null
        return formatZone(key, zoneId)
    }

    private fun formatZone(city: String, zoneId: String): String {
        return runCatching {
            val now = ZonedDateTime.now(ZoneId.of(zoneId))
            val time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
            val pretty = city.split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { it.uppercase(Locale.US) }
            }
            "In $pretty it's $time"
        }.getOrNull() ?: return ""
    }

    private fun keyName(key: String): String =
        key.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase(Locale.US) } }
}
