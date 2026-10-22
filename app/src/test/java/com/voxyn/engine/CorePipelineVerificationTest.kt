package com.voxyn.engine

import com.voxyn.intent.IntentRouter
import com.voxyn.perception.AppNameResolver
import com.voxyn.perception.SpeechNormalizer
import com.voxyn.router.RequestRouter
import com.voxyn.router.RouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CorePipelineVerificationTest {

    private lateinit var normalizer: SpeechNormalizer
    private lateinit var intentRouter: IntentRouter
    private lateinit var requestRouter: RequestRouter

    @Before
    fun setup() {
        val fakeResolver = object : AppNameResolver {
            override fun resolveLabel(spoken: String): String? = when (spoken.lowercase()) {
                "whatsapp", "whats app", "wasapp" -> "WhatsApp"
                "chatgpt", "chat jeep tee", "chat gpt" -> "ChatGPT"
                "hungerbox", "hunger books", "hungry box", "hunger box" -> "HungerBox"
                "youtube", "you do", "your job", "you tube" -> "YouTube"
                "instagram", "insta" -> "Instagram"
                else -> null
            }
        }
        normalizer = SpeechNormalizer(fakeResolver)
        intentRouter = IntentRouter()
        requestRouter = RequestRouter()
    }

    @Test
    fun `1 - open WhatsApp command resolution`() {
        val raw = "open whats app"
        val norm = normalizer.normalize(raw)
        val intent = intentRouter.route(norm)
        assertEquals("OPEN_APP", intent.action)
        assertEquals("whatsapp", intent.params["app_name"]?.lowercase())
    }

    @Test
    fun `2 - open chat gpt with phonetic distortion`() {
        val raw = "open chat jeep tee"
        val norm = normalizer.normalize(raw)
        val intent = intentRouter.route(norm)
        assertEquals("OPEN_APP", intent.action)
        assertEquals("chatgpt", intent.params["app_name"]?.lowercase())
    }

    @Test
    fun `3 - open HungerBox with rhotacism distortion`() {
        val raw = "open hunger books"
        val norm = normalizer.normalize(raw)
        val intent = intentRouter.route(norm)
        assertEquals("OPEN_APP", intent.action)
        assertEquals("hungerbox", intent.params["app_name"]?.lowercase())
    }

    @Test
    fun `4 - what time is it local query`() {
        val raw = "what time is it"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.FAST_COMMAND, route.type)

        val intent = intentRouter.route(norm)
        assertEquals("TIME", intent.action)
    }

    @Test
    fun `5 - what time is it in New York world time query`() {
        val raw = "what time is it in New York"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.CURRENT_INFORMATION, route.type)
        assertEquals("new york", route.params["city"])
    }

    @Test
    fun `6 - whats the latest news query`() {
        val raw = "what's the latest news"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.WEB_REQUIRED, route.type)
    }

    @Test
    fun `7 - what is quantum computing general AI query`() {
        val raw = "what is quantum computing"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.CONVERSATIONAL, route.type)
    }

    @Test
    fun `8 - send Rahul a WhatsApp saying Ill be late`() {
        val raw = "send Rahul a WhatsApp message saying I'll be late"
        val norm = normalizer.normalize(raw)
        val intent = intentRouter.route(norm)
        assertEquals("SEND_WHATSAPP_MESSAGE", intent.action)
        assertEquals("rahul", intent.params["recipient"]?.lowercase())
        assertTrue(intent.params["message"]?.contains("late", ignoreCase = true) == true)

        val raw2 = "message Mom on WhatsApp saying I'm coming home"
        val norm2 = normalizer.normalize(raw2)
        val intent2 = intentRouter.route(norm2)
        assertEquals("SEND_WHATSAPP_MESSAGE", intent2.action)
        assertEquals("mom", intent2.params["recipient"]?.lowercase())
        assertTrue(intent2.params["message"]?.contains("coming home", ignoreCase = true) == true)
    }

    @Test
    fun `9 - distance from Chennai to Bangalore routing`() {
        val raw = "distance from Chennai to Bangalore"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.DISTANCE, route.type)
        assertEquals("chennai", route.params["origin"]?.lowercase())
        assertEquals("bangalore", route.params["destination"]?.lowercase())
    }

    @Test
    fun `10 - remember Rahuls meeting is Friday`() {
        val raw = "remember that Rahul's meeting is Friday"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.MEMORY_SAVE, route.type)
        assertTrue(route.params["note"]?.contains("Rahul's meeting is Friday", ignoreCase = true) == true)
    }

    @Test
    fun `11 - when is Rahuls meeting memory recall`() {
        val raw = "when is Rahul's meeting?"
        val norm = normalizer.normalize(raw)
        val route = requestRouter.route(norm)
        assertEquals(RouteType.MEMORY_RECALL, route.type)
        assertTrue(route.params["topic"]?.contains("Rahul's meeting", ignoreCase = true) == true)
    }

    @Test
    fun `12 - take a photo camera command`() {
        val raw = "take a photo"
        val norm = normalizer.normalize(raw)
        val intent = intentRouter.route(norm)
        assertEquals("TAKE_PHOTO", intent.action)

        val rawTimed = "take a picture in 10 seconds"
        val normTimed = normalizer.normalize(rawTimed)
        val intentTimed = intentRouter.route(normTimed)
        assertEquals("TAKE_PHOTO", intentTimed.action)
        assertEquals("10", intentTimed.params["delay_seconds"])
    }
}
