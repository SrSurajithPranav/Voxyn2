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
}
