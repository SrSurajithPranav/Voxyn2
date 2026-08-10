package com.voxyn.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommunicationPolicyTest {

    @Test
    fun `explicit sms channel stays sms`() {
        assertEquals("sms", CommunicationPolicy.resolveChannel("sms", whatsappInstalled = true))
    }

    @Test
    fun `explicit whatsapp channel stays whatsapp when installed`() {
        assertEquals("whatsapp", CommunicationPolicy.resolveChannel("whatsapp", whatsappInstalled = true))
    }

    @Test
    fun `whatsapp requested but not installed falls back to sms`() {
        assertEquals("sms", CommunicationPolicy.resolveChannel("whatsapp", whatsappInstalled = false))
    }

    @Test
    fun `no channel defaults to sms`() {
        assertEquals("sms", CommunicationPolicy.resolveChannel(null, whatsappInstalled = true))
    }

    @Test
    fun `wa url includes country code and encoded text`() {
        val url = CommunicationPolicy.buildWhatsAppUrl("+919876543210", "i'll be there")
        assertNotNull(url)
        assertEquals("https://wa.me/919876543210?text=i%27ll+be+there", url)
    }

    @Test
    fun `wa url requires country code`() {
        assertNull(CommunicationPolicy.buildWhatsAppUrl("9876543210", "hello"))
    }
}
