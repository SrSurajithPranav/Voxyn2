package com.voxyn.communication

import com.voxyn.intent.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic intent-routing tests for the communication milestone.
 * Inputs mirror SpeechNormalizer output (lowercase, no [unk]).
 */
class CommunicationIntentTest {

    private val router = IntentRouter()

    @Test
    fun `message rahul saying i'll be late routes to SEND_MESSAGE`() {
        val result = router.route("message rahul saying i'll be late")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("i'll be late", result.params["message"])
        assertEquals("sms", result.params["channel"])
    }

    @Test
    fun `send whatsapp to rahul i'm coming routes to SEND_WHATSAPP_MESSAGE`() {
        val result = router.route("send whatsapp to rahul i'm coming")
        assertEquals("SEND_WHATSAPP_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("i'm coming", result.params["message"])
        assertEquals("whatsapp", result.params["channel"])
    }

    @Test
    fun `whatsapp rahul i'm on my way routes to SEND_WHATSAPP_MESSAGE`() {
        val result = router.route("whatsapp rahul i'm on my way")
        assertEquals("SEND_WHATSAPP_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("i'm on my way", result.params["message"])
        assertEquals("whatsapp", result.params["channel"])
    }

    @Test
    fun `send rahul a whatsapp saying i'll reach by 7 routes to SEND_WHATSAPP_MESSAGE`() {
        val result = router.route("send rahul a whatsapp saying i'll reach by 7")
        assertEquals("SEND_WHATSAPP_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("i'll reach by 7", result.params["message"])
        assertEquals("whatsapp", result.params["channel"])
    }

    @Test
    fun `call rahul routes to CALL_CONTACT`() {
        val result = router.route("call rahul")
        assertEquals("CALL_CONTACT", result.action)
        assertEquals("rahul", result.params["contact"])
    }

    @Test
    fun `call mom routes to CALL_CONTACT`() {
        val result = router.route("call mom")
        assertEquals("CALL_CONTACT", result.action)
        assertEquals("mom", result.params["contact"])
    }

    @Test
    fun `message dad that i'll reach home at 8 routes to SEND_MESSAGE`() {
        val result = router.route("message dad that i'll reach home at 8")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("dad", result.params["recipient"])
        assertEquals("i'll reach home at 8", result.params["message"])
    }

    @Test
    fun `text dad that i'll reach home at 8 routes to SEND_MESSAGE`() {
        val result = router.route("text dad that i'll reach home at 8")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("dad", result.params["recipient"])
        assertEquals("i'll reach home at 8", result.params["message"])
    }

    @Test
    fun `tell rahul i'll be late routes to SEND_MESSAGE`() {
        val result = router.route("tell rahul i'll be late")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("i'll be late", result.params["message"])
    }

    @Test
    fun `send this to mom routes to SEND_MESSAGE with screen marker`() {
        val result = router.route("send this to mom")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("mom", result.params["recipient"])
        assertEquals("<screen>", result.params["message"])
    }

    @Test
    fun `missing contact - message saying hi has no recipient`() {
        val result = router.route("message saying hi")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("hi", result.params["message"])
        assertFalse(result.params.containsKey("recipient"))
    }

    @Test
    fun `missing body - message rahul has no message`() {
        val result = router.route("message rahul")
        assertEquals("SEND_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertFalse(result.params.containsKey("message"))
    }

    @Test
    fun `send whatsapp to rahul without body has no message`() {
        val result = router.route("send whatsapp to rahul")
        assertEquals("SEND_WHATSAPP_MESSAGE", result.action)
        assertEquals("rahul", result.params["recipient"])
        assertEquals("whatsapp", result.params["channel"])
        assertFalse(result.params.containsKey("message"))
    }

    @Test
    fun `unsupported messaging app routes to SEND_UNSUPPORTED_CHANNEL`() {
        val result = router.route("send telegram message to rahul saying hi")
        assertEquals("SEND_UNSUPPORTED_CHANNEL", result.action)
        assertEquals("telegram", result.params["channel"])
        assertEquals("rahul", result.params["recipient"])
        assertEquals("hi", result.params["message"])
    }

    @Test
    fun `group request routes to MESSAGE_GROUP`() {
        val result = router.route("message the family group saying dinner is ready")
        assertEquals("MESSAGE_GROUP", result.action)
        assertEquals("family group", result.params["group"])
        assertEquals("dinner is ready", result.params["message"])
    }

    @Test
    fun `group request without the routes to MESSAGE_GROUP`() {
        val result = router.route("message family group dinner is ready")
        assertEquals("MESSAGE_GROUP", result.action)
        assertEquals("family group", result.params["group"])
        assertEquals("dinner is ready", result.params["message"])
    }

    @Test
    fun `open whatsapp still routes to OPEN_APP`() {
        val result = router.route("open whatsapp")
        assertEquals("OPEN_APP", result.action)
        assertEquals("whatsapp", result.params["app_name"])
    }

    @Test
    fun `send it still routes to SEND_CURRENT_MESSAGE`() {
        val result = router.route("send it")
        assertEquals("SEND_CURRENT_MESSAGE", result.action)
    }

    @Test
    fun `find rahul routes to CONTACT_LOOKUP`() {
        val result = router.route("find rahul")
        assertEquals("CONTACT_LOOKUP", result.action)
        assertEquals("rahul", result.params["contact"])
    }

    @Test
    fun `look up rahul's number routes to CONTACT_LOOKUP`() {
        val result = router.route("look up rahul's number")
        assertEquals("CONTACT_LOOKUP", result.action)
        assertEquals("rahul", result.params["contact"])
    }
}
