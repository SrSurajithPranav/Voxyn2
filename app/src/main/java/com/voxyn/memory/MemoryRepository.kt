package com.voxyn.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voxyn_memory")

class MemoryRepository(private val context: Context) {

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

    // ─── Personal memory notes ("remember that my meeting is Friday") ────

    private val notesKey = stringPreferencesKey("memory_notes_json")

    suspend fun saveNote(content: String) {
        val notes = notesList().toMutableList()
        notes.add(content.trim())
        context.dataStore.edit { prefs ->
            prefs[notesKey] = JSONArray(notes).toString()
        }
        VoxynLogger.i("Memory note saved: \"$content\"", "MEMORY")
    }

    suspend fun notesList(): List<String> = runCatching {
        val raw = context.dataStore.data.first()[notesKey] ?: return emptyList()
        val arr = JSONArray(raw)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }.getOrDefault(emptyList())

    /** Notes whose text shares any meaningful word with the spoken topic. */
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

    private suspend fun getInt(key: String): Int {
        return context.dataStore.data.first()[intPreferencesKey(key)] ?: 0
    }

    private suspend fun setInt(key: String, value: Int) {
        context.dataStore.edit { prefs ->
            prefs[intPreferencesKey(key)] = value
        }
    }
}
