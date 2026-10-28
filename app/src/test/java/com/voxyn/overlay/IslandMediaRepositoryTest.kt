package com.voxyn.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMediaRepositoryTest {
    @Test fun permissionAndSessionChangesClearStaleMetadataAndControls() {
        val repo = IslandMediaRepository()
        assertFalse(repo.state.value.accessGranted)
        repo.updateAccess(true)
        assertTrue(repo.state.value.accessGranted)
        repo.updateSnapshot(IslandMediaState(sessionAvailable = true, title = "Track", artist = "Artist", isPlaying = true, canTogglePlayback = true))
        assertEquals("Track", repo.state.value.title)
        assertTrue(repo.state.value.canTogglePlayback)
        repo.updateSnapshot(IslandMediaState(accessGranted = true))
        assertFalse(repo.state.value.sessionAvailable)
        assertEquals(null, repo.state.value.title)
        repo.updateAccess(false)
        assertEquals(IslandMediaState(), repo.state.value)
    }
}
