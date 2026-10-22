package com.voxyn.ai

import com.voxyn.voice.ConversationSession
import com.voxyn.voice.ConversationTurn
import com.voxyn.web.DistanceProvider
import com.voxyn.web.DistanceResult
import com.voxyn.web.DuckDuckGoSearchProvider
import com.voxyn.web.NewsSearchProvider
import com.voxyn.web.WeatherProvider
import com.voxyn.web.WeatherResult
import com.voxyn.web.WebSearchResult
import com.voxyn.web.WikipediaSearchProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline fake providers — no network in tests. */
private class FakeWeatherProvider : WeatherProvider {
    var lastLocation: String? = null
    var lastDay: Int = -1

    override suspend fun forecast(location: String, dayOffset: Int): WeatherResult? {
        lastLocation = location
        lastDay = dayOffset
        return WeatherResult("In $location it's warm.", location, dayOffset)
    }
}

private class FakeDistanceProvider : DistanceProvider {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun distance(origin: String, destination: String): DistanceResult? {
        calls.add(origin to destination)
        return DistanceResult("About 300 kilometres.", 300.0, null)
    }
}
private class FakeNewsProvider : NewsSearchProvider() {
    override suspend fun search(query: String): WebSearchResult? =
        WebSearchResult("Headline one. Headline two.", "Fake News")
}

private class FakeLocalAi : AiProvider {
    override val name: String = "Fake local model"
    var calls = 0
    override suspend fun answer(question: String, conversationContext: String): AiResponse? {
        calls++
        return AiResponse("A stale model answer.", name)
    }
}

private class FakeDuckDuckGoProvider : DuckDuckGoSearchProvider() {
    var calls = 0
    override suspend fun search(query: String): WebSearchResult? {
        calls++
        return WebSearchResult("Current source-backed answer.", "Fake Web", "https://example.test/current")
    }
}

class HybridGeneralResolverTest {

    private fun resolver(
        weather: FakeWeatherProvider = FakeWeatherProvider(),
        distance: FakeDistanceProvider = FakeDistanceProvider(),
        session: ConversationSession = ConversationSession()
    ): Triple<HybridGeneralResolver, FakeWeatherProvider, FakeDistanceProvider> {
        val r = HybridGeneralResolver(
            localLlm = null,
            localKnowledge = LocalKnowledgeProvider(),
            worldTime = WorldTimeProvider(),
            wikipedia = WikipediaSearchProvider(),
            news = NewsSearchProvider(),
            duckDuckGo = DuckDuckGoSearchProvider(),
            weather = weather,
            distance = distance,
            session = session
        )
        return Triple(r, weather, distance)
    }

    @Test
    fun `weather question goes to weather provider`() = runBlocking {
        val (r, w, _) = resolver()
        val result = r.resolve("what's the weather in chennai", "")
        assertNotNull(result)
        assertEquals("chennai", w.lastLocation)
        assertEquals(0, w.lastDay)
        assertTrue(result!!.text.contains("chennai"))
    }

    @Test
    fun `weather follow-up reuses last location`() = runBlocking {
        val session = ConversationSession()
        session.addTurn(ConversationTurn(
            userInput = "what's the weather in chennai",
            action = "WEATHER",
            response = "In Chennai it's warm.",
            params = mapOf("kind" to "weather", "location" to "chennai", "day" to "0")
        ))
        val (r, w, _) = resolver(session = session)
        val result = r.resolve("what about tomorrow", "")
        assertNotNull(result)
        assertEquals("chennai", w.lastLocation)
        assertEquals(1, w.lastDay)
        assertTrue(result!!.text.contains("warm"))
    }

    @Test
    fun `distance follow-up keeps origin`() = runBlocking {
        val session = ConversationSession()
        session.addTurn(ConversationTurn(
            userInput = "distance between chennai and bangalore",
            action = "DISTANCE",
            response = "About 300 kilometres.",
            params = mapOf("kind" to "distance", "origin" to "chennai", "destination" to "bangalore")
        ))
        val (r, _, d) = resolver(session = session)
        val result = r.resolve("what about hyderabad", "")
        assertNotNull(result)
        assertEquals(listOf("chennai" to "hyderabad"), d.calls)
    }

    @Test
    fun `world time answers offline`() = runBlocking {
        val (r, _, _) = resolver()
        val result = r.resolve("what time is it in tokyo", "")
        assertNotNull(result)
        assertTrue(result!!.text.contains("Tokyo"))
        assertEquals(mapOf("kind" to "time", "location" to "tokyo"), result.params)

        val resultUs = r.resolve("what time is it in the US", "")
        assertNotNull(resultUs)
        assertTrue(resultUs!!.text.contains("multiple time zones"))
    }

    @Test
    fun `multi-stop distance calculation resolves via waypoints`() = runBlocking {
        val (r, _, d) = resolver()
        val result = r.resolve("how far is chennai to bangalore via hyderabad", "")
        assertNotNull(result)
        assertEquals(listOf("chennai" to "hyderabad", "hyderabad" to "bangalore"), d.calls)
        assertTrue(result!!.text.contains("Chennai to Hyderabad"))
        assertTrue(result.text.contains("Hyderabad to Bangalore"))
        assertTrue(result.text.contains("Total around 600 kilometres"))
    }

    @Test
    fun `news question routes to news provider`() = runBlocking {
        val r = HybridGeneralResolver(
            localLlm = null,
            localKnowledge = LocalKnowledgeProvider(),
            worldTime = WorldTimeProvider(),
            wikipedia = WikipediaSearchProvider(),
            news = FakeNewsProvider(),
            duckDuckGo = DuckDuckGoSearchProvider(),
            weather = FakeWeatherProvider(),
            distance = FakeDistanceProvider(),
            session = ConversationSession()
        )
        val result = r.resolve("what's today's news", "")
        assertNotNull(result)
        assertTrue(result!!.text.contains("Headline"))
    }

    @Test
    fun `unknown question falls through gracefully`() = runBlocking {
        val r = HybridGeneralResolver(
            localLlm = null,
            localKnowledge = LocalKnowledgeProvider(),
            worldTime = WorldTimeProvider(),
            wikipedia = WikipediaSearchProvider(),
            news = NewsSearchProvider(),
            duckDuckGo = DuckDuckGoSearchProvider(),
            weather = FakeWeatherProvider(),
            distance = FakeDistanceProvider(),
            session = ConversationSession()
        )
        val result = r.resolve("zyzzx mystery question", "")
        assertNull(result)
    }

    @Test
    fun `joke is answered locally`() = runBlocking {
        val (r, _, _) = resolver()
        val result = r.resolve("tell me a joke", "")
        assertNotNull(result)
    }

    @Test
    fun `current facts use web before the local model and retain provenance`() = runBlocking {
        val local = FakeLocalAi()
        val web = FakeDuckDuckGoProvider()
        val resolver = HybridGeneralResolver(
            localLlm = local,
            localKnowledge = LocalKnowledgeProvider(),
            worldTime = WorldTimeProvider(),
            wikipedia = WikipediaSearchProvider(),
            news = NewsSearchProvider(),
            duckDuckGo = web,
            weather = FakeWeatherProvider(),
            distance = FakeDistanceProvider(),
            session = ConversationSession()
        )

        val result = resolver.resolve("who is the president right now", "")
        assertEquals(0, local.calls)
        assertEquals(1, web.calls)
        assertEquals("Fake Web", result?.source)
        assertEquals("https://example.test/current", result?.sourceUrl)
        assertEquals(IntelligenceRoute.WEB, result?.route)
    }

    @Test
    fun `explicit web requests do not fall back to the local model`() = runBlocking {
        val local = FakeLocalAi()
        val web = FakeDuckDuckGoProvider()
        val resolver = HybridGeneralResolver(
            localLlm = local,
            localKnowledge = LocalKnowledgeProvider(),
            worldTime = WorldTimeProvider(),
            wikipedia = WikipediaSearchProvider(),
            news = NewsSearchProvider(),
            duckDuckGo = web,
            weather = FakeWeatherProvider(),
            distance = FakeDistanceProvider(),
            session = ConversationSession()
        )

        resolver.resolve("best cafes in Chennai", "", forceWeb = true)
        assertEquals(0, local.calls)
        assertEquals(1, web.calls)
    }
}
