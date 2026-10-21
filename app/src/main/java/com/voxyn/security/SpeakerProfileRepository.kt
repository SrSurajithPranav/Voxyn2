package com.voxyn.security

import android.content.Context
import com.voxyn.utils.VoxynLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages persistence for up to 5 enrolled speaker profiles.
 * Profiles are stored locally in app-private storage (filesDir/speaker_profiles.json).
 */
class SpeakerProfileRepository(
    private val context: Context? = null,
    private val baseDir: File? = null
) {

    companion object {
        const val MAX_PROFILES = 5
        private const val PROFILES_FILE = "speaker_profiles.json"
        const val OWNER_ID = "owner"
    }

    private val profiles = CopyOnWriteArrayList<SpeakerProfile>()

    init {
        loadProfiles()
    }

    fun getAllProfiles(): List<SpeakerProfile> = profiles.toList()

    fun getProfile(id: String): SpeakerProfile? = profiles.firstOrNull { it.id == id }

    fun getOwnerProfile(): SpeakerProfile? = profiles.firstOrNull { it.isOwner }

    fun isEnrolled(): Boolean = profiles.isNotEmpty()

    @Synchronized
    fun enrollProfile(profile: SpeakerProfile): Boolean {
        if (profiles.size >= MAX_PROFILES && !profiles.any { it.id == profile.id }) {
            VoxynLogger.w("Cannot enroll speaker: maximum of $MAX_PROFILES profiles reached", "SPEAKER")
            return false
        }
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
        saveProfiles()
        VoxynLogger.i("Speaker enrolled: \"${profile.name}\" (id=${profile.id}, isOwner=${profile.isOwner})", "SPEAKER")
        return true
    }

    @Synchronized
    fun removeProfile(id: String): Boolean {
        val removed = profiles.removeAll { it.id == id }
        if (removed) {
            saveProfiles()
            VoxynLogger.i("Speaker profile removed: $id", "SPEAKER")
        }
        return removed
    }

    @Synchronized
    fun clearProfiles() {
        profiles.clear()
        saveProfiles()
        VoxynLogger.i("All speaker profiles cleared", "SPEAKER")
    }

    private fun getStorageFile(): File =
        if (baseDir != null) File(baseDir, PROFILES_FILE)
        else File(context?.filesDir ?: File("."), PROFILES_FILE)

    private fun loadProfiles() {
        val file = getStorageFile()
        if (!file.exists()) {
            return
        }
        try {
            val jsonStr = file.readText(Charsets.UTF_8)
            val arr = JSONArray(jsonStr)
            profiles.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val isOwner = obj.optBoolean("isOwner", false)
                val enrolledAt = obj.optLong("enrolledAtMs", System.currentTimeMillis())
                val vpArr = obj.getJSONArray("voiceprint")
                val voiceprint = FloatArray(vpArr.length()) { idx -> vpArr.getDouble(idx).toFloat() }
                profiles.add(SpeakerProfile(id, name, isOwner, voiceprint, enrolledAt))
            }
            VoxynLogger.i("Loaded ${profiles.size} enrolled speaker profiles", "SPEAKER")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to load speaker profiles: ${e.message}", tag = "SPEAKER")
        }
    }

    private fun saveProfiles() {
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("isOwner", p.isOwner)
                    put("enrolledAtMs", p.enrolledAtMs)
                    val vpArr = JSONArray()
                    p.voiceprint.forEach { vpArr.put(it.toDouble()) }
                    put("voiceprint", vpArr)
                }
                arr.put(obj)
            }
            getStorageFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            VoxynLogger.e("Failed to save speaker profiles: ${e.message}", tag = "SPEAKER")
        }
    }
}
