package com.voxyn.web

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OsmDistanceProvider — keyless geographic distance:
 *
 *   Nominatim (OSM geocoding) → lat/lon for origin and destination
 *   OSRM public router      → driving distance + duration
 *   Haversine               → straight-line fallback when routing fails
 *
 * Distances are always stated ("about 346 kilometres") and never invented.
 */
class OsmDistanceProvider : DistanceProvider {

    override suspend fun distance(origin: String, destination: String): DistanceResult? {
        val o = origin.trim().lowercase()
        val d = destination.trim().lowercase()
        if (o.isBlank() || d.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val oo = geocode(o) ?: return@runCatching null
                val dd = geocode(d) ?: return@runCatching null
                val (km, seconds) = route(oo, dd)
                val prettyOrigin = pretty(o)
                val prettyDest = pretty(d)
                val text = if (seconds != null) {
                    "About ${roundKm(km)} kilometres by road from $prettyOrigin to $prettyDest, roughly ${durationText(seconds)}."
                } else {
                    "About ${roundKm(km)} kilometres straight line between $prettyOrigin and $prettyDest."
                }
                VoxynLogger.i("[WEB] Distance: $prettyOrigin → $prettyDest = $km km", "WEB")
                DistanceResult(text, km, seconds)
            }.getOrNull()
        }
    }

    private suspend fun geocode(city: String): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
        }
        if (conn.responseCode != 200) {
            VoxynLogger.w("Nominatim HTTP ${conn.responseCode}", "WEB")
            return null
        }
        val text = conn.inputStream.bufferedReader().readText()
        val list = JSONArray(text)
        if (list.length() == 0) return null
        val first = list.optJSONObject(0) ?: return null
        val lat = first.optDouble("lat")
        val lon = first.optDouble("lon")
        return if (lat == 0.0 && lon == 0.0) null else lat to lon
    }

    /** Returns (kilometres, driving seconds) — seconds null when routing failed. */
    private suspend fun route(from: Pair<Double, Double>, to: Pair<Double, Double>): Pair<Double, Long?> {
        runCatching {
            val (lat1, lon1) = from
            val (lat2, lon2) = to
            val url = "https://router.project-osrm.org/route/v1/driving/$lon1,$lat1;$lon2,$lat2?overview=false&alternatives=false"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val routes = json.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val route = routes.optJSONObject(0)
                    val distanceMeters = route?.optDouble("distance")
                    val durationSec = route?.optDouble("duration")
                    if (distanceMeters != null && distanceMeters > 0) {
                        return (distanceMeters / 1000.0) to (durationSec?.toLong())
                    }
                }
            }
            VoxynLogger.w("OSRM routing failed — using straight-line", "WEB")
        }
        val (lat1, lon1) = from
        val (lat2, lon2) = to
        return haversineKm(lat1, lon1, lat2, lon2) to null
    }

    private fun pretty(city: String): String =
        city.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun roundKm(km: Double): Int = maxOf(1, Math.round(km).toInt())

    private fun durationText(seconds: Long): String {
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        return when {
            hours > 0 && mins > 0 -> "$hours hours $mins minutes"
            hours > 0 -> "$hours hours"
            else -> "$mins minutes"
        }
    }

    /** Great-circle distance between two coordinates, in kilometres. */
    internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
