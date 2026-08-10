package com.voxyn.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory ContactStore used to fake contact data on the JVM. */
class FakeContactStore(private val contacts: List<Pair<String, String>>) : ContactStore {
    override fun getAllContactsWithPhone(): List<Pair<String, String>> = contacts
}

class ContactResolverTest {

    private val store = FakeContactStore(
        listOf(
            "rahul kumar" to "+919876543210",
            "rahul sharma" to "+919811122233",
            "mom" to "+919000000001",
            "ravi" to "+919000000002"
        )
    )

    private val resolver = ContactResolver(store)

    @Test
    fun `unique contact resolves immediately`() {
        val res = resolver.resolve("rahul kumar")
        assertTrue(res is ContactResolution.Unique)
        res as ContactResolution.Unique
        assertEquals("rahul kumar", res.displayName)
        assertEquals("+919876543210", res.phoneNumber)
    }

    @Test
    fun `ambiguous contact returns both candidates`() {
        val res = resolver.resolve("rahul")
        assertTrue(res is ContactResolution.Ambiguous)
        res as ContactResolution.Ambiguous
        assertEquals(2, res.candidates.size)
        assertTrue(res.candidates.contains("rahul kumar" to "+919876543210"))
        assertTrue(res.candidates.contains("rahul sharma" to "+919811122233"))
    }

    @Test
    fun `missing contact returns NotFound`() {
        val res = resolver.resolve("sachin")
        assertTrue(res is ContactResolution.NotFound)
    }

    @Test
    fun `alias mom resolves to contact mom`() {
        val res = resolver.resolve("mom")
        assertTrue(res is ContactResolution.Unique)
        res as ContactResolution.Unique
        assertEquals("mom", res.displayName)
    }

    @Test
    fun `raw phone number passes through as direct number`() {
        val res = resolver.resolve("9876543210")
        assertTrue(res is ContactResolution.Unique)
        res as ContactResolution.Unique
        assertEquals("9876543210", res.phoneNumber)
    }

    @Test
    fun `case insensitive fuzzy match works`() {
        val res = resolver.resolve("RAHUL KUMAR")
        assertTrue(res is ContactResolution.Unique)
    }
}
