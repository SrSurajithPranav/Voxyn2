package com.voxyn.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTimeProviderTest {

    private val provider = WorldTimeProvider()

    @Test
    fun `known city returns formatted time`() {
        val text = provider.timeIn("new york")
        assertNotNull(text)
        assertTrue(text.orEmpty().contains("New York"))
    }

    @Test
    fun `single-zone country returns one time`() {
        assertNotNull(provider.timeIn("india"))
    }

    @Test
    fun `multi-zone country lists every zone explicitly`() {
        val text = provider.timeIn("us")
        assertNotNull(text)
        assertTrue(text.orEmpty().contains("multiple time zones"))
        assertTrue(text.orEmpty().contains("New York"))
        assertTrue(text.orEmpty().contains("Los Angeles"))
    }

    @Test
    fun `same zones for usa and united states`() {
        val a = provider.timeIn("usa").orEmpty()
        val b = provider.timeIn("united states").orEmpty()
        assertTrue(a.contains("multiple time zones"))
        assertTrue(b.contains("multiple time zones"))
        assertTrue(a.contains("New York"))
        assertTrue(b.contains("New York"))
        assertTrue(a.contains("Los Angeles"))
        assertTrue(b.contains("Los Angeles"))
    }

    @Test
    fun `unknown place returns null`() {
        assertNull(provider.timeIn("atlantis"))
    }
}
