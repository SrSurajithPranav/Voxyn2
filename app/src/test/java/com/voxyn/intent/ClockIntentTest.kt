package com.voxyn.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockIntentTest {
    private val router = IntentRouter()

    @Test
    fun `alarm time parses AM and PM correctly`() {
        val pm = router.route("set an alarm for 7:30 PM")
        assertEquals("SET_ALARM", pm.action)
        assertEquals("19", pm.params["hour"])
        assertEquals("30", pm.params["minutes"])
        assertTrue("needs_clarification" !in pm.params)

        val midnight = router.alarmTimeParams("12:05 AM")
        assertEquals("0", midnight?.get("hour"))
        assertEquals("5", midnight?.get("minutes"))
    }

    @Test
    fun `ambiguous alarm hour asks for AM or PM and missing hour asks for time`() {
        val ambiguous = router.route("wake me up at 7")
        assertEquals("7", ambiguous.params["hour"])
        assertEquals("AM or PM", ambiguous.params["needs_clarification"])

        val missing = router.route("set an alarm")
        assertEquals("time", missing.params["needs_clarification"])
    }

    @Test
    fun `timer duration is parsed and missing units are clarified`() {
        val timer = router.route("set a timer for 2 hours")
        assertEquals("SET_TIMER", timer.action)
        assertEquals("7200", timer.params["length_seconds"])

        val ambiguous = router.route("set a timer for 5")
        assertEquals("unit", ambiguous.params["needs_clarification"])

        val missing = router.route("set a timer")
        assertEquals("duration", missing.params["needs_clarification"])
    }

    @Test
    fun `show timers and alarms route to existing clock capability`() {
        assertEquals("SHOW_TIMERS", router.route("show timers").action)
        assertEquals("SHOW_ALARMS", router.route("open my alarms").action)
    }
}
