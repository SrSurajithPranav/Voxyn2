package com.voxyn.perception

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeAppNameResolver : AppNameResolver {
    private val apps = mapOf(
        "whatsapp" to "WhatsApp",
        "youtube" to "YouTube",
        "instagram" to "Instagram",
        "insta" to "Instagram",
        "chatgpt" to "ChatGPT",
        "hungerbox" to "HungerBox",
        "hungerbooks" to "HungerBox",
        "x" to "X",
        "twitter" to "X",
        "telegram" to "Telegram",
        "calculator" to "Calculator"
    )

    override fun resolveLabel(spoken: String): String? {
        val key = spoken.trim().lowercase().replace(Regex("[^a-z0-9]+"), "")
        return apps[key]
    }
}

class SpeechNormalizerTest {

    private val normalizer = SpeechNormalizer(FakeAppNameResolver())

    @Test
    fun `open whats app becomes open WhatsApp`() {
        assertEquals("open WhatsApp", normalizer.normalize("open whats app"))
    }

    @Test
    fun `open chat gpt becomes open ChatGPT`() {
        assertEquals("open ChatGPT", normalizer.normalize("open chat gpt"))
    }

    @Test
    fun `open you tube becomes open YouTube`() {
        assertEquals("open YouTube", normalizer.normalize("open you tube"))
    }

    @Test
    fun `open insta becomes open Instagram`() {
        assertEquals("open Instagram", normalizer.normalize("open insta"))
    }

    @Test
    fun `open hunger books becomes open HungerBox`() {
        assertEquals("open HungerBox", normalizer.normalize("open hunger books"))
    }

    @Test
    fun `open twitter becomes open X`() {
        assertEquals("open X", normalizer.normalize("open twitter"))
    }

    @Test
    fun `bare whatsapp means open WhatsApp`() {
        assertEquals("open WhatsApp", normalizer.normalize("whatsapp"))
    }

    @Test
    fun `conversation phrases are left untouched`() {
        assertEquals("how are you", normalizer.normalize("how are you"))
        assertEquals("what is the weather in paris", normalizer.normalize("what is the weather in paris"))
        assertEquals("tell me a joke", normalizer.normalize("tell me a joke"))
    }

    @Test
    fun `unrelated phrases are not turned into app opens`() {
        assertEquals("what's app development", normalizer.normalize("what's app development"))
        assertEquals("set a timer for 10 minutes", normalizer.normalize("set a timer for 10 minutes"))
        assertEquals("time sheet is due on friday", normalizer.normalize("time sheet is due on friday"))
    }

    @Test
    fun `unknown app name is left as open command`() {
        assertEquals("open zoomly", normalizer.normalize("open zoomly"))
    }
}
