package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.execution.capabilities.ModeCapability
import com.voxyn.mode.AssistantMode
import com.voxyn.mode.ModeEngine
import com.voxyn.voice.TextToSpeechManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeCapabilityTest {

    @Test
    fun `mode capability metadata and action support`() = runBlocking {
        val tts = TextToSpeechManager(android.content.ContextWrapper(null))
        val modeEngine = ModeEngine(android.content.ContextWrapper(null), tts)
        val cap = ModeCapability(modeEngine)

        assertEquals("mode", cap.id)
        assertTrue(cap.supportedActions.contains("MODE_ACTIVATE"))
    }
}
