package com.voxyn.voice

import android.content.Context
import com.voxyn.utils.VoxynLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Private, no-backup storage for the five enrollment slots and their recordings.
 * Raw recordings never enter logs and are kept out of shared storage.
 */
class VoiceProfileRepository(private val context: Context) {
    companion object {
        const val SLOT_COUNT = 5
        private const val METADATA_FILE = "voice_profiles.json"
        private const val RECORDINGS_DIR = "voice_profiles"
        private const val SELECTED_VOICE_KEY = "selected_reply_voice_id"
    }

    private val metadataFile get() = File(context.noBackupFilesDir, METADATA_FILE)
    private val recordingsDir get() = File(context.noBackupFilesDir, RECORDINGS_DIR).apply { mkdirs() }
    private val embeddingsDir get() = File(context.noBackupFilesDir, "speaker_embeddings").apply { mkdirs() }
    private val preferences by lazy { context.getSharedPreferences("voice_output", Context.MODE_PRIVATE) }

    @Synchronized
    fun list(): List<VoiceProfile> {
        val stored = runCatching { JSONArray(metadataFile.takeIf { it.exists() }?.readText().orEmpty()) }.getOrNull()
            ?: JSONArray()
        return (0 until SLOT_COUNT).map { slot ->
            val obj = (0 until stored.length()).asSequence()
                .map { stored.optJSONObject(it) }
                .firstOrNull { it?.optInt("slot", -1) == slot }
            VoiceProfile(
                slot = slot,
                id = obj?.optString("id").orEmpty().ifBlank { "slot-${slot + 1}" },
                label = obj?.optString("label").orEmpty().ifBlank { "Voice ${slot + 1}" },
                enrolled = obj?.optBoolean("enrolled", false) == true,
                sampleCount = obj?.optInt("sampleCount", 0) ?: 0,
                samplePath = obj?.optString("samplePath")?.takeIf { !it.isNullOrBlank() },
                updatedAt = obj?.optLong("updatedAt")?.takeIf { it > 0L },
                speakerEnrolled = obj?.optBoolean("speakerEnrolled", false) == true && obj?.optBoolean("enrolled", false) == true
            )
        }
    }

    fun recordingFile(profileId: String): File = File(recordingsDir, "$profileId.wav")
    fun embeddingFile(profileId: String): File = File(embeddingsDir, "$profileId.f32")

    @Synchronized
    fun saveEmbedding(profileId: String, embedding: FloatArray) {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(embedding.isNotEmpty() && embedding.all(Float::isFinite))
        val file = embeddingFile(profileId)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().buffered().use { out ->
            val bytes = java.nio.ByteBuffer.allocate(embedding.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            embedding.forEach(bytes::putFloat)
            out.write(bytes.array())
        }
        check(temp.renameTo(file)) { "Could not commit speaker embedding" }
    }

    @Synchronized
    fun markSpeakerEnrolled(profileId: String) {
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == profileId && it.enrolled }
        if (index < 0) error("Voice profile is not enrolled")
        current[index] = current[index].copy(speakerEnrolled = true)
        write(current)
    }

    fun loadEmbedding(profileId: String): FloatArray? = runCatching {
        val bytes = embeddingFile(profileId).readBytes()
        require(bytes.isNotEmpty() && bytes.size % 4 == 0)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer().let { buffer -> FloatArray(buffer.remaining()) { buffer.get() } }
            .takeIf { values -> values.all(Float::isFinite) }
    }.getOrNull()

    fun hasEmbedding(profileId: String): Boolean = embeddingFile(profileId).isFile

    @Synchronized
    fun saveEnrollment(slot: Int, label: String, sampleCount: Int): VoiceProfile {
        require(slot in 0 until SLOT_COUNT)
        val current = list().toMutableList()
        val prior = current[slot]
        val profile = prior.copy(
            id = prior.id.ifBlank { UUID.randomUUID().toString() },
            label = label.trim().ifBlank { "Voice ${slot + 1}" }.take(48),
            enrolled = true,
            sampleCount = sampleCount,
            samplePath = recordingFile(prior.id).absolutePath,
            updatedAt = System.currentTimeMillis(),
            speakerEnrolled = true
        )
        current[slot] = profile
        write(current)
        return profile
    }

    @Synchronized
    fun clear(slot: Int) {
        val profile = list().getOrNull(slot) ?: return
        recordingFile(profile.id).delete()
        embeddingFile(profile.id).delete()
        val current = list().toMutableList()
        current[slot] = profile.copy(enrolled = false, sampleCount = 0, samplePath = null, updatedAt = null, speakerEnrolled = false)
        write(current)
        if (preferences.getString(SELECTED_VOICE_KEY, null) == profile.id) {
            preferences.edit().remove(SELECTED_VOICE_KEY).apply()
        }
    }

    fun selectedReplyVoiceId(): String? = preferences.getString(SELECTED_VOICE_KEY, null)

    fun setSelectedReplyVoice(id: String?) {
        if (id == null) preferences.edit().remove(SELECTED_VOICE_KEY).apply()
        else if (list().any { it.id == id }) preferences.edit().putString(SELECTED_VOICE_KEY, id).apply()
    }

    private fun write(profiles: List<VoiceProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("slot", p.slot)
                put("id", p.id)
                put("label", p.label)
                put("enrolled", p.enrolled)
                put("sampleCount", p.sampleCount)
                put("samplePath", p.samplePath)
                put("updatedAt", p.updatedAt)
                put("speakerEnrolled", p.speakerEnrolled)
            })
        }
        runCatching { metadataFile.writeText(array.toString()) }
            .onFailure { VoxynLogger.e("Voice profile metadata write failed: ${it.message}", tag = "VOICE_PROFILE") }
    }
}
