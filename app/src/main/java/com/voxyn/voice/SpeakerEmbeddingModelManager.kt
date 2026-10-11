package com.voxyn.voice

import android.content.Context
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the published Apache-2.0 3D-Speaker ERes2Net model to private no-backup storage. */
class SpeakerEmbeddingModelManager(context: Context) {
    companion object {
        const val MODEL_FILE = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL_FILE"
        private const val MIN_MODEL_BYTES = 1_000_000L
    }

    private val modelFile = File(File(context.noBackupFilesDir, "speaker_model"), MODEL_FILE)

    fun fileIfPresent(): File? = modelFile.takeIf { it.isFile && it.length() >= MIN_MODEL_BYTES }

    suspend fun ensureAvailable(): File? = withContext(Dispatchers.IO) {
        fileIfPresent()?.let { return@withContext it }
        val directory = modelFile.parentFile ?: return@withContext null
        if (!directory.exists() && !directory.mkdirs()) return@withContext null
        val temporary = File(directory, "$MODEL_FILE.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) return@withContext null
            connection.inputStream.use { input -> temporary.outputStream().buffered().use(input::copyTo) }
            if (temporary.length() < MIN_MODEL_BYTES || !temporary.renameTo(modelFile)) {
                temporary.delete()
                return@withContext null
            }
            VoxynLogger.i("Speaker embedding model downloaded (${modelFile.length()} bytes)", "SPEAKER")
            modelFile
        } catch (error: Exception) {
            temporary.delete()
            VoxynLogger.e("Speaker embedding model download failed: ${error.javaClass.simpleName}", tag = "SPEAKER")
            null
        } finally {
            connection.disconnect()
        }
    }
}
