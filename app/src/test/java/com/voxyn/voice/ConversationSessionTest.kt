package com.voxyn.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
