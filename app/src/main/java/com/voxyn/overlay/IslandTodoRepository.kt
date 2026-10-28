package com.voxyn.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal val ISLAND_TODO_ITEMS = stringPreferencesKey("island_todo_items")

data class IslandTodo(val id: String, val title: String, val completed: Boolean)

/** Small local-only list for tasks explicitly entered by the user. */
class IslandTodoRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope
) {
    private val _items = MutableStateFlow<List<IslandTodo>>(emptyList())
    val items: StateFlow<List<IslandTodo>> = _items.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collect { prefs -> _items.value = decode(prefs[ITEMS].orEmpty()) }
        }
    }

    suspend fun add(title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty() || clean.length > MAX_TITLE_LENGTH) return false
        val item = IslandTodo(UUID.randomUUID().toString(), clean, false)
        dataStore.edit { prefs -> prefs[ITEMS] = encode(decode(prefs[ITEMS].orEmpty()) + item) }
        return true
    }

    suspend fun setCompleted(id: String, completed: Boolean): Boolean = update(id) { it.copy(completed = completed) }
    suspend fun delete(id: String): Boolean {
        var found = false
        dataStore.edit { prefs ->
            val current = decode(prefs[ITEMS].orEmpty())
            found = current.any { it.id == id }
            if (found) prefs[ITEMS] = encode(current.filterNot { it.id == id })
        }
        return found
    }

    private suspend fun update(id: String, transform: (IslandTodo) -> IslandTodo): Boolean {
        var found = false
        dataStore.edit { prefs ->
            val current = decode(prefs[ITEMS].orEmpty())
            found = current.any { it.id == id }
            if (found) prefs[ITEMS] = encode(current.map { if (it.id == id) transform(it) else it })
        }
        return found
    }

    private fun encode(items: List<IslandTodo>): String = items.joinToString(",") {
        "${it.id}:${Base64.getUrlEncoder().withoutPadding().encodeToString(it.title.toByteArray(StandardCharsets.UTF_8))}:${if (it.completed) 1 else 0}"
    }

    private fun decode(serialized: String): List<IslandTodo> = serialized.split(',').mapNotNull { row ->
        val parts = row.split(':', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        runCatching {
            IslandTodo(parts[0], String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), parts[2] == "1")
        }.getOrNull()
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 120
        val ITEMS = ISLAND_TODO_ITEMS
    }
}
