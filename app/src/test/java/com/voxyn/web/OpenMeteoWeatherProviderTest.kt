package com.voxyn.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoWeatherProviderTest {

    private val provider = OpenMeteoWeatherProvider()

    @Test
    fun `day words map to offsets`() {
        assertEquals(0, WeatherDay.offsetFrom("today"))
        assertEquals(1, WeatherDay.offsetFrom("tomorrow"))
        assertEquals(2, WeatherDay.offsetFrom("the day after"))
        assertNull(WeatherDay.offsetFrom("next week"))
    }

    @Test
    fun `day labels are tts friendly`() {
        assertEquals("today", WeatherDay.label(0))
        assertEquals("tomorrow", WeatherDay.label(1))
        assertEquals("the day after tomorrow", WeatherDay.label(2))
    }

    @Test
    fun `weather codes map to short descriptions`() {
        assertEquals("clear skies", provider.describeWeatherCode(0))
        assertEquals("partly cloudy", provider.describeWeatherCode(2))
        assertEquals("rain", provider.describeWeatherCode(63))
        assertEquals("a thunderstorm", provider.describeWeatherCode(95))
    }
}
