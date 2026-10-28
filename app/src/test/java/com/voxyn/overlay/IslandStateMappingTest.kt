package com.voxyn.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandStateMappingTest {
    @Test fun processingAndExecutionRemainDistinct() {
        assertEquals(IslandState.PROCESSING, islandStateFromPipeline("PROCESSING"))
        assertEquals(IslandState.EXECUTING, islandStateFromPipeline("EXECUTING"))
        assertEquals(IslandState.PLANNING, islandStateFromPipeline("PLANNING"))
        assertEquals(IslandState.VERIFYING, islandStateFromPipeline("VERIFYING"))
    }

    @Test fun unknownEventsAreNotSilentlyMappedToIdle() {
        assertNull(islandStateFromPipeline("MADE_UP"))
    }
}
