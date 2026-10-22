package com.voxyn.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voxyn_memory")

data class ExplicitMemory(
    val id: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
)

class MemoryRepository(private val context: Context) {

    // ─── Trust / Outcome Memory ──────────────────────────────────────────────

    suspend fun getTrustScore(action: String): Float {
        val success = getInt("${action}_success")
        val failure = getInt("${action}_failure")
        val total = success + failure
        return if (total == 0) 0.5f
        else (success.toFloat() / total).coerceIn(0.1f, 0.95f)
    }

    suspend fun recordOutcome(action: String, success: Boolean) {
        val key = if (success) "${action}_success" else "${action}_failure"
        val current = getInt(key)
        setInt(key, current + 1)
        VoxynLogger.i(
            "Memory: $action ${if (success) "✓" else "✗"} " +
            "trust=${getTrustScore(action)}", "MEMORY"
        )
    }

    // ─── Explicit User Notes & Facts ("remember that my gym is at 6 PM") ─────

    private val notesKey = stringPreferencesKey("memory_notes_json")
    private val preferencesKey = stringPreferencesKey("user_preferences_json")

    suspend fun saveNote(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        val current = notesList().toMutableList()
        current.add(trimmed)
        context.dataStore.edit { prefs ->
            prefs[notesKey] = JSONArray(current).toString()
        }
        VoxynLogger.i("Explicit memory saved: \"$trimmed\"", "MEMORY")
    }

    suspend fun notesList(): List<String> = runCatching {
        val raw = context.dataStore.data.first()[notesKey] ?: return emptyList()
        val arr = JSONArray(raw)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }.getOrDefault(emptyList())

    /** Recalls explicit memories matching keywords or topic. */
    suspend fun recallNotes(topic: String): List<String> {
        val all = notesList()
        if (all.isEmpty()) return emptyList()
        val words = topic.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (words.isEmpty()) return all
        return all.filter { note ->
            val n = note.lowercase()
            words.any { n.contains(it) }
        }
    }

    /** Removes explicit memories matching a given topic. Returns number of removed notes. */
    suspend fun forgetNotes(topic: String): Int {
        val current = notesList()
        if (current.isEmpty()) return 0

        val words = topic.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val remaining = if (words.isEmpty()) {
            emptyList()
        } else {
            current.filterNot { note ->
                val n = note.lowercase()
                words.any { n.contains(it) }
            }
        }

        val removedCount = current.size - remaining.size
        if (removedCount > 0) {
            context.dataStore.edit { prefs ->
                prefs[notesKey] = JSONArray(remaining).toString()
            }
            VoxynLogger.i("Forgot $removedCount memory notes matching '$topic'", "MEMORY")
        }
        return removedCount
    }

    /** Removes the most recently added memory note. Returns true if removed. */
    suspend fun forgetLast(): Boolean {
        val current = notesList().toMutableList()
        if (current.isEmpty()) return false
        val removed = current.removeLastOrNull()
        if (removed != null) {
            context.dataStore.edit { prefs ->
                prefs[notesKey] = JSONArray(current).toString()
            }
            VoxynLogger.i("Forgot last memory note: '$removed'", "MEMORY")
            return true
        }
        return false
    }

    // ─── User Preferences Memory ─────────────────────────────────────────────

    suspend fun setPreference(key: String, value: String) {
        val current = getAllPreferences().toMutableMap()
        current[key.trim().lowercase()] = value.trim()
        val obj = JSONObject(current as Map<*, *>)
        context.dataStore.edit { prefs ->
            prefs[preferencesKey] = obj.toString()
        }
        VoxynLogger.i("Preference set: $key -> $value", "MEMORY")
    }

    suspend fun getPreference(key: String): String? {
        val all = getAllPreferences()
        return all[key.trim().lowercase()]
    }

    suspend fun getAllPreferences(): Map<String, String> = runCatching {
        val raw = context.dataStore.data.first()[preferencesKey] ?: return emptyMap()
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, obj.getString(k))
            }
        }
    }.getOrDefault(emptyMap())

    // ─── DataStore Helpers ───────────────────────────────────────────────────

    private suspend fun getInt(key: String): Int {
        return context.dataStore.data.first()[intPreferencesKey(key)] ?: 0
    }

    private suspend fun setInt(key: String, value: Int) {
        context.dataStore.edit { prefs ->
            prefs[intPreferencesKey(key)] = value
        }
    }
}
