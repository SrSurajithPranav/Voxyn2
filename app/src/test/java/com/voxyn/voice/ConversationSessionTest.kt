package com.voxyn.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.voxyn.wake.WakeDetectionOutcome
import com.voxyn.wake.WakeWordHandoff
import com.voxyn.wake.wakeDetectionOutcome

class ConversationSessionTest {

    @Test
    fun `session retains a bounded recent conversation`() {
        val session = ConversationSession()

        repeat(12) { index ->
            session.addTurn(ConversationTurn("request $index", "GENERAL", "reply $index"))
        }

        assertEquals(8, session.turns.size)
        assertEquals("request 4", session.turns.first().userInput)
        assertTrue(session.getContext().contains("request 11"))
    }

    @Test
    fun `ending a session clears retained context`() {
        val session = ConversationSession()
        session.addTurn(ConversationTurn("what time is it", "TIME", "It's noon."))

        session.end()

        assertFalse(session.isActive)
        assertEquals("", session.getContext())
    }

    @Test
    fun `adding a pending turn does not revive an ended session`() {
        val session = ConversationSession()

        session.end()
        session.addTurn(ConversationTurn("turn on the light", "", ""))

        assertFalse(session.isActive)
        assertFalse(session.isWithinWindow())
    }

    @Test
    fun `updating the completed turn activates the conversation window`() {
        val session = ConversationSession()
        session.addTurn(ConversationTurn("turn on the light", "", ""))

        session.updateLastTurn("FLASHLIGHT_ON", "Flashlight on.")

        assertTrue(session.isActive)
        assertTrue(session.isWithinWindow())
        assertEquals("FLASHLIGHT_ON", session.lastTurn()?.action)
    }

    @Test
    fun `two completed follow-up commands keep the conversation window open`() {
        val clock = FakeClock()
        val session = ConversationSession(clock::now)

        completeTurn(session, "turn on the light", "LIGHT_ON", clock)
        clock.advance(10_000L)
        completeTurn(session, "make it brighter", "BRIGHTER", clock)
        clock.advance(10_000L)
        completeTurn(session, "turn it off", "LIGHT_OFF", clock)

        assertEquals(3, session.turns.size)
        assertEquals("LIGHT_OFF", session.lastTurn()?.action)
        assertTrue(session.isWithinWindow())
        assertEquals(
            ConversationContinuation.FOLLOW_UP_LISTENING,
            conversationContinuation(session)
        )
    }

    @Test
    fun `silence after the 30-second window says standing by`() {
        val clock = FakeClock()
        val session = ConversationSession(clock::now)
        completeTurn(session, "what time is it", "TIME", clock)

        clock.advance(session.windowMs)

        assertFalse(session.isWithinWindow())
        assertEquals(
            ConversationContinuation.STANDING_BY,
            conversationContinuation(session)
        )
    }

    @Test
    fun `acknowledgement completion starts command STT only once`() {
        val handoff = WakeWordHandoff()
        var starts = 0

        assertTrue(handoff.startAfterAcknowledgement(stopped = false) { starts++ })
        assertFalse(handoff.startAfterAcknowledgement(stopped = false) { starts++ })

        assertEquals(1, starts)
    }

    @Test
    fun `microphone denial is not retried as a recognizer failure`() {
        assertEquals(
            WakeDetectionOutcome.MICROPHONE_PERMISSION_REQUIRED,
            wakeDetectionOutcome(SecurityException("permission denied"))
        )
        assertEquals(
            WakeDetectionOutcome.RETRY,
            wakeDetectionOutcome(IllegalStateException("audio device unavailable"))
        )
    }

    @Test
    fun `recognizer failure resumes wake service at most once`() {
        val recovery = VoiceInputRecovery()
        var resumes = 0

        assertTrue(recovery.resumeOnce { resumes++ })
        assertFalse(recovery.resumeOnce { resumes++ })

        assertEquals(1, resumes)
    }

    @Test
    fun `TTS failure completion releases its handoff callback once`() {
        var completions = 0
        val completion = TtsCompletionGate { completions++ }

        completion.complete(TtsCompletionReason.FAILED)
        completion.complete()

        assertEquals(1, completions)
        assertEquals(TtsCompletionReason.FAILED, completion.completionReason)
    }

    @Test
    fun `null intent restart returns control to wake detection`() {
        assertTrue(shouldResumeWakeAfterRestart(null))
        assertFalse(shouldResumeWakeAfterRestart(VoiceInputService.ACTION_START_LISTENING))
    }

    private fun completeTurn(
        session: ConversationSession,
        input: String,
        action: String,
        clock: FakeClock
    ) {
        session.addTurn(ConversationTurn(input, "", ""))
        session.updateLastTurn(action, "$action response")
        assertEquals(clock.now, session.lastInteractionTime)
    }

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }
}
