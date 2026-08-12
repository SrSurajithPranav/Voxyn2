package com.voxyn.web

/**
 * DistanceProvider — geographic distance behind an interface. Implementations
 * may use routing (road distance) with a straight-line fallback.
 */
data class DistanceResult(
    val text: String,
    val km: Double,
    val seconds: Long? = null
)

interface DistanceProvider {
    suspend fun distance(origin: String, destination: String): DistanceResult?
}
