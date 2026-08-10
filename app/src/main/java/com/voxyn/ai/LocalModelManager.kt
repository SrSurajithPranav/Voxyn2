package com.voxyn.ai

import android.content.Context
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalModelManager — downloads and exposes the on-device LLM model file
 * (MediaPipe .task format) inside the app's private storage. No storage
 * permissions needed because the file lives in filesDir.
 */
class LocalModelManager(private val context: Context) {

    companion object {
        // Gemma 2B int4 (~1.4 GB). Swap this URL for any other MediaPipe .task
        // model, e.g. a smaller llama-3.2-1b or tinyllama file from HuggingFace.
        const val MODEL_URL = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/genai/gemma-2b-it-gpu-int4.task"
        const val MODEL_FILE_NAME = "llm.task"
        private const val MIN_VALID_SIZE = 1_000_000L
    }

    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isModelPresent(): Boolean =
        modelFile.exists() && modelFile.length() > MIN_VALID_SIZE

    /** Streams the model to filesDir; reports 0..1 progress. */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            modelFile.parentFile?.mkdirs()
            val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.part")
            tmp.delete()

            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
            }
            if (conn.responseCode != 200) {
                VoxynLogger.e("Model download HTTP ${conn.responseCode}", tag = "AI")
                return@runCatching false
            }

            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val p = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            onProgress(p)
                        }
                    }
                }
            }

            val ok = tmp.length() > MIN_VALID_SIZE && tmp.renameTo(modelFile)
            if (ok) {
                VoxynLogger.i("Local LLM model ready: ${modelFile.length() / 1_000_000} MB", "AI")
            } else {
                tmp.delete()
                VoxynLogger.e("Model download produced an invalid file", tag = "AI")
            }
            ok
        }.getOrDefault(false)
    }
}
