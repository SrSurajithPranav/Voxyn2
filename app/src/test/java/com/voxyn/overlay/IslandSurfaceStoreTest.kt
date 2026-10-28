package com.voxyn.overlay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IslandSurfaceStoreTest {
    @Test
    fun voiceEventsExpansionAndDeviceEventsUpdateSharedState() = runBlocking {
        val fixture = fixture()
        try {
            val store = fixture.store
            store.setVoiceState(IslandState.LISTENING)
            assertEquals(IslandState.LISTENING, store.state.value.voiceState)
            store.beginCapture("test-capture")
            store.setFinalTranscript("test-capture", "Turn on the lights")
            store.setVoiceState(IslandState.PROCESSING, "Planning action")
            store.setVerificationResult("LIGHTS_ON", "Verified")
            store.setVoiceState(IslandState.SUCCESS, "Lights on")
            assertEquals("LIGHTS_ON", store.state.value.recentAction)
            assertEquals("Verified", store.state.value.verificationOutcome)
            store.setExpanded(true)
            store.setBattery(73, true)
            store.setHeadphonesConnected(true)
            assertTrue(store.state.value.expanded)
            store.setExpanded(false)
            assertFalse(store.state.value.expanded)
            assertEquals(73, store.state.value.batteryPercent)
            assertTrue(store.state.value.charging)
            assertTrue(store.state.value.headphonesConnected)
            store.setVoiceState(IslandState.RECOVERING)
            assertEquals(IslandState.RECOVERING, store.state.value.voiceState)
            IslandState.values().forEach { transition ->
                store.setVoiceState(transition)
                assertEquals(transition, store.state.value.voiceState)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialFinalAndStaleCaptureEventsAreHandled() = runBlocking {
        val fixture = fixture()
        try {
            val store = fixture.store
            store.beginCapture("capture-1")
            assertTrue(store.setPartialTranscript("capture-1", "Turn on"))
            assertEquals(IslandState.PARTIAL_TRANSCRIPT, store.state.value.voiceState)
            assertEquals("Turn on", store.state.value.partialTranscript)
            store.beginCapture("capture-2")
            assertFalse(store.setPartialTranscript("capture-1", "stale text"))
            assertTrue(store.setPartialTranscript("capture-2", "Turn on the lights"))
            assertTrue(store.setFinalTranscript("capture-2", "Turn on the lights"))
            assertEquals("Turn on the lights", store.state.value.verifiedTranscript)
            assertEquals("", store.state.value.partialTranscript)
            assertFalse(store.setPartialTranscript("capture-2", "late partial"))
        } finally { fixture.close() }
    }

    @Test
    fun newerPipelineGenerationRejectsStaleEvents() = runBlocking {
        val fixture = fixture()
        try {
            val store = fixture.store
            store.beginPipeline(8)
            store.beginPipeline(9)
            assertFalse(store.setVoiceState(IslandState.SUCCESS, "Old success", 8))
            assertTrue(store.setVoiceState(IslandState.EXECUTING, "Running", 9))
            assertEquals(IslandState.EXECUTING, store.state.value.voiceState)
        } finally { fixture.close() }
    }

    @Test
    fun mascotAndMinimalNotchPreferencesSurviveStoreRecreation() = runBlocking {
        val fixture = fixture()
        try {
            fixture.store.setMascotEnabled(false)
            fixture.store.setMinimalNotch(true)
            val reloaded = IslandSurfaceStore(fixture.dataStore, fixture.scope)
            val persisted = withTimeout(2_000) {
                reloaded.state.first { !it.mascotEnabled && it.minimalNotch }
            }
            assertFalse(persisted.mascotEnabled)
            assertTrue(persisted.minimalNotch)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(): Fixture {
        val directory = Files.createTempDirectory("voxyn-island-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "island.preferences_pb") })
        return Fixture(directory, scope, dataStore, IslandSurfaceStore(dataStore, scope))
    }

    private data class Fixture(
        val directory: File,
        val scope: CoroutineScope,
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        val store: IslandSurfaceStore
    ) {
        fun close() {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
