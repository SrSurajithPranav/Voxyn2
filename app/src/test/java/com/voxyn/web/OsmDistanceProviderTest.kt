package com.voxyn.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmDistanceProviderTest {

    private val provider = OsmDistanceProvider()

    @Test
    fun `haversine chennai to bangalore is roughly 290 km`() {
        val km = provider.haversineKm(13.0827, 80.2707, 12.9716, 77.5946)
        assertTrue("expected ~290 km, got $km", km in 270.0..320.0)
    }

    @Test
    fun `haversine of identical points is zero`() {
        assertEquals(0.0, provider.haversineKm(13.0, 80.0, 13.0, 80.0), 0.001)
    }
}
