package com.voxyn.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRouterTest {

    private val router = RequestRouter()

    @Test
    fun `deterministic commands stay fast`() {
        assertEquals(RouteType.FAST_COMMAND, router.route("open whatsapp").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("call rahul").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("set a timer for 10 minutes").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("turn on wifi").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("navigate to bangalore").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("take a photo").type)
        assertEquals(RouteType.FAST_COMMAND, router.route("record a video for 30 seconds").type)
    }

    @Test
    fun `weather questions route to weather`() {
        val r = router.route("what's the weather in chennai")
        assertEquals(RouteType.WEATHER, r.type)
        assertEquals("chennai", r.params["location"])
    }

    @Test
    fun `plain weather in x routes to weather`() {
        assertEquals(RouteType.WEATHER, router.route("weather in chennai").type)
    }

    @Test
    fun `how hot is it in x routes to weather`() {
        assertEquals(RouteType.WEATHER, router.route("how hot is it in chennai").type)
    }

    @Test
    fun `distance between routes to distance`() {
        val r = router.route("distance between chennai and bangalore")
        assertEquals(RouteType.DISTANCE, r.type)
        assertEquals("chennai", r.params["origin"])
        assertEquals("bangalore", r.params["destination"])
    }

    @Test
    fun `how far is a from b routes to distance`() {
        assertEquals(RouteType.DISTANCE, router.route("how far is chennai from bangalore").type)
    }

    @Test
    fun `how far is a to b via c routes to distance`() {
        val r = router.route("how far is chennai to bangalore via hyderabad")
        assertEquals(RouteType.DISTANCE, r.type)
        assertEquals("chennai", r.params["origin"])
        assertEquals("bangalore via hyderabad", r.params["destination"])
    }

    @Test
    fun `which is closer routes to distance`() {
        assertEquals(RouteType.DISTANCE, router.route("which is closer to chennai bangalore or kochi").type)
    }

    @Test
    fun `world time routes to current information`() {
        val r = router.route("what time is it in tokyo")
        assertEquals(RouteType.CURRENT_INFORMATION, r.type)
        assertEquals("tokyo", r.params["city"])

        val rUs = router.route("what time is it in the US")
        assertEquals(RouteType.CURRENT_INFORMATION, rUs.type)
        assertEquals("the us", rUs.params["city"]?.lowercase())
    }

    @Test
    fun `news questions route to web required`() {
        assertEquals(RouteType.WEB_REQUIRED, router.route("what's today's news").type)
        assertEquals(RouteType.WEB_REQUIRED, router.route("what's happening in india today").type)
        assertEquals(RouteType.WEB_REQUIRED, router.route("what's the latest AI news").type)
    }

    @Test
    fun `explicit web search routes to web required`() {
        val r = router.route("search the internet for best cafes in chennai")
        assertEquals(RouteType.WEB_REQUIRED, r.type)
        assertTrue(r.params["query"].orEmpty().contains("best cafes"))
    }

    @Test
    fun `general knowledge routes to conversational`() {
        assertEquals(RouteType.CONVERSATIONAL, router.route("what is an api").type)
        assertEquals(RouteType.CONVERSATIONAL, router.route("explain black holes").type)
        assertEquals(RouteType.CONVERSATIONAL, router.route("explain quantum computing").type)
    }

    @Test
    fun `memory routes stay intact`() {
        assertEquals(RouteType.MEMORY_SAVE, router.route("remember that my meeting is friday").type)
        assertEquals(RouteType.MEMORY_SAVE, router.route("remember that Rahul's birthday is Friday").type)
        assertEquals(RouteType.MEMORY_RECALL, router.route("what did I tell you about rahul").type)
    }
}
