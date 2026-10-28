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

class IslandTodoRepositoryTest {
    @Test fun addCompleteDeleteAndReloadPersistUserTasks() = runBlocking {
        val dir = Files.createTempDirectory("voxyn-todos").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "todos.preferences_pb") })
            val repo = IslandTodoRepository(store, scope)
            assertFalse(repo.add("  "))
            assertTrue(repo.add("Buy milk"))
            val item = withTimeout(2_000) { repo.items.first { it.isNotEmpty() }.single() }
            assertTrue(repo.setCompleted(item.id, true))
            val completed = withTimeout(2_000) { repo.items.first { it.singleOrNull()?.completed == true }.single() }
            assertEquals(item.id, completed.id)
            val persisted = withTimeout(2_000) { store.data.first { it[ISLAND_TODO_ITEMS].orEmpty().contains(":1") } }
            assertTrue(persisted[ISLAND_TODO_ITEMS].orEmpty().contains("Buy milk".encodeBase64()))
            assertTrue(repo.delete(item.id))
            assertTrue(withTimeout(2_000) { repo.items.first { it.isEmpty() } }.isEmpty())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    private fun String.encodeBase64(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
}
