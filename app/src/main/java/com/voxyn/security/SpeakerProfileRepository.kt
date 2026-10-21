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
        const val MAX_PROFILES = 25
        const val MAX_SAMPLES_PER_PROFILE = 25
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
    fun addSampleToProfile(profileId: String, newVoiceprint: FloatArray): Boolean {
        val existing = profiles.firstOrNull { it.id == profileId } ?: return false
        val currentSamples = existing.sampleVoiceprints.toMutableList()
        if (currentSamples.size >= MAX_SAMPLES_PER_PROFILE) {
            currentSamples.removeAt(0)
        }
        currentSamples.add(newVoiceprint)

        // Compute centroid voiceprint across all samples
        val centroid = FloatArray(SpeakerVerifier.EMBEDDING_DIM)
        for (sample in currentSamples) {
            for (i in 0 until SpeakerVerifier.EMBEDDING_DIM) {
                centroid[i] += sample[i]
            }
        }
        var norm = 0f
        for (i in 0 until SpeakerVerifier.EMBEDDING_DIM) {
            centroid[i] /= currentSamples.size.toFloat()
            norm += centroid[i] * centroid[i]
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) {
            for (i in 0 until SpeakerVerifier.EMBEDDING_DIM) centroid[i] /= norm
        }

        val updated = existing.copy(
            voiceprint = centroid,
            sampleVoiceprints = currentSamples
        )
        profiles.removeAll { it.id == profileId }
        profiles.add(updated)
        saveProfiles()
        VoxynLogger.i("Added sample to profile '$profileId' (total samples: ${currentSamples.size})", "SPEAKER")
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
                val pitch = obj.optDouble("pitch", 1.0).toFloat()
                val speechRate = obj.optDouble("speechRate", 1.0).toFloat()
                val timbre = obj.optString("timbre", if (pitch > 1.05f) "female" else "male")
                val vpArr = obj.getJSONArray("voiceprint")
                val voiceprint = FloatArray(vpArr.length()) { idx -> vpArr.getDouble(idx).toFloat() }
                if (voiceprint.size != SpeakerVerifier.EMBEDDING_DIM) {
                    VoxynLogger.w("Ignoring legacy ${voiceprint.size}-dim profile '$name' (re-enroll for MFCC precision)", "SPEAKER")
                    continue
                }

                val samples = mutableListOf<FloatArray>()
                val samplesJson = obj.optJSONArray("samples")
                if (samplesJson != null) {
                    for (s in 0 until samplesJson.length()) {
                        val sArr = samplesJson.getJSONArray(s)
                        if (sArr.length() == SpeakerVerifier.EMBEDDING_DIM) {
                            samples.add(FloatArray(sArr.length()) { idx -> sArr.getDouble(idx).toFloat() })
                        }
                    }
                }
                if (samples.isEmpty()) {
                    samples.add(voiceprint)
                }

                profiles.add(SpeakerProfile(id, name, isOwner, voiceprint, enrolledAt, pitch, speechRate, timbre, samples))
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
                    put("pitch", p.pitch.toDouble())
                    put("speechRate", p.speechRate.toDouble())
                    put("timbre", p.timbre)
                    val vpArr = JSONArray()
                    p.voiceprint.forEach { vpArr.put(it.toDouble()) }
                    put("voiceprint", vpArr)

                    val samplesArr = JSONArray()
                    for (sample in p.sampleVoiceprints) {
                        val sArr = JSONArray()
                        sample.forEach { sArr.put(it.toDouble()) }
                        samplesArr.put(sArr)
                    }
                    put("samples", samplesArr)
                }
                arr.put(obj)
            }
            getStorageFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            VoxynLogger.e("Failed to save speaker profiles: ${e.message}", tag = "SPEAKER")
        }
    }
}
