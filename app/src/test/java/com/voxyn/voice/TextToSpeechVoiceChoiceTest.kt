package com.voxyn.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TextToSpeechVoiceChoiceTest {

    @Test
    fun `exactly five supported reply voices exist`() {
        val choices = VoiceChoice.values()
        assertEquals(5, choices.size)
        assertEquals(VoiceChoice.SYSTEM_DEFAULT, choices[0])
        assertEquals(VoiceChoice.JARVIS, choices[1])
        assertEquals(VoiceChoice.FRIDAY, choices[2])
        assertEquals(VoiceChoice.CADENCE, choices[3])
        assertEquals(VoiceChoice.EXPRESSIVE, choices[4])
    }

    @Test
    fun `fromOrdinal and fromName resolve correctly`() {
        assertEquals(VoiceChoice.JARVIS, VoiceChoice.fromOrdinal(1))
        assertEquals(VoiceChoice.FRIDAY, VoiceChoice.fromName("friday"))
        assertEquals(VoiceChoice.CADENCE, VoiceChoice.fromName("Cadence (Smooth)"))
        assertEquals(VoiceChoice.SYSTEM_DEFAULT, VoiceChoice.fromName("nonexistent"))
    }

    @Test
    fun `voice parameters are tuned for acoustic distinction`() {
        // JARVIS has lower pitch for deep warm authority
        assertEquals(0.88f, VoiceChoice.JARVIS.pitch, 0.01f)
        // FRIDAY has higher pitch for crisp clarity
        assertEquals(1.15f, VoiceChoice.FRIDAY.pitch, 0.01f)
        // Dynamic / Expressive has elevated pitch and rate
        assertEquals(1.08f, VoiceChoice.EXPRESSIVE.pitch, 0.01f)
        assertEquals(1.10f, VoiceChoice.EXPRESSIVE.speechRate, 0.01f)
    }

    @Test
    fun `cycling through voice choices rotates through all five choices`() {
        val all = VoiceChoice.values()
        for (i in all.indices) {
            val current = all[i]
            val nextOrdinal = (current.ordinal + 1) % all.size
            val next = VoiceChoice.fromOrdinal(nextOrdinal)
            val expected = all[(i + 1) % all.size]
            assertEquals(expected, next)
        }
    }
}
