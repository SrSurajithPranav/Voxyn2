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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IslandActivityRepositoryTest {
    @Test fun reminderPersistsSchedulesAndCancellationPreventsDelivery() = runBlocking {
        val dir = Files.createTempDirectory("voxyn-activities").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scheduler = FakeScheduler()
        var now = 10_000_000L
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "activities.preferences_pb") })
            val repo = IslandActivityRepository(store, scope, scheduler) { now }
            assertNull(repo.schedule(IslandActivityKind.REMINDER, "", 3))
            assertNull(repo.schedule(IslandActivityKind.REMINDER, "Bad duration", 0))
            val item = repo.schedule(IslandActivityKind.REMINDER, "Call home", 5)!!
            assertEquals(item, withTimeout(2_000) { repo.activities.first { it.isNotEmpty() }.single() })
            assertEquals(item, scheduler.scheduled[item.id])
            assertTrue(store.data.first()[ISLAND_ACTIVITIES].orEmpty().contains("Call home".base64()))
            now = item.dueAtMillis
            assertEquals(item, repo.completeFromAlarm(item.id))
            assertNull(repo.completeFromAlarm(item.id))

            val cancellable = repo.schedule(IslandActivityKind.FOCUS_TIMER, "Focus", 10)!!
            assertTrue(repo.cancel(cancellable.id))
            assertNull(repo.completeFromAlarm(cancellable.id))
            assertFalse(scheduler.scheduled.containsKey(cancellable.id))
            assertTrue(withTimeout(2_000) { repo.activities.first { it.isEmpty() } }.isEmpty())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    private fun String.base64() = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private class FakeScheduler : IslandActivityAlarmScheduler {
        val scheduled = mutableMapOf<String, IslandActivity>()
        override fun schedule(activity: IslandActivity) { scheduled[activity.id] = activity }
        override fun cancel(id: String) { scheduled.remove(id) }
    }
}
