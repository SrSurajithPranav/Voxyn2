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
 * DownloadOutcome — result of a model download attempt with a user-facing
 * reason so failures are diagnosable instead of a generic "failed".
 */
data class DownloadOutcome(val ok: Boolean, val message: String)

/**
 * LocalModelManager — downloads and exposes the on-device LLM model file
 * (MediaPipe .task / flatbuffer model) inside the app's private storage.
 * No storage permissions needed because the file lives in filesDir.
 *
 * The original Google-hosted model URL now returns 403, so the downloader
 * tries working HuggingFace mirrors in order and falls back to a CPU build
 * of the same Gemma 2B int4 model for devices without a supported GPU.
 */
class LocalModelManager(private val context: Context) {

    companion object {
        // Gemma 2B int4 (GPU delegate). If one mirror dies, the next is tried.
        val MODEL_URLS = listOf(
            "https://huggingface.co/vba01/gemma-2b-it-gpu-int4/resolve/main/gemma-2b-it-gpu-int4.bin",
            "https://huggingface.co/autoocrat0413/gemma-2b-it-gpu-int4-mediapipe/resolve/main/gemma-2b-it-gpu-int4.bin",
            "https://huggingface.co/autoocrat0413/gemma-2b-it-gpu-int4-mediapipe/resolve/main/gemma-2b-it-cpu-int4.bin"
        )
        const val MODEL_FILE_NAME = "llm.task"
        private const val MIN_VALID_SIZE = 1_000_000L
    }

    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isModelPresent(): Boolean =
        modelFile.exists() && isValidModel(modelFile)

    /** Streams the model to filesDir; reports 0..1 progress for the active mirror. */
    suspend fun downloadModel(onProgress: (Float) -> Unit): DownloadOutcome = withContext(Dispatchers.IO) {
        modelFile.parentFile?.mkdirs()

        for (url in MODEL_URLS) {
            val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.part")
            tmp.delete()
            var downloadedTotal = 0L
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
                }
                val code = conn.responseCode
                if (code != 200) {
                    VoxynLogger.e("Model download HTTP $code from $url", tag = "AI")
                    continue
                }

                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            downloadedTotal += read
                            if (total > 0) {
                                onProgress((downloadedTotal.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                if (downloadedTotal > MIN_VALID_SIZE && isValidModel(tmp) && tmp.renameTo(modelFile)) {
                    VoxynLogger.i("Local LLM model ready: ${modelFile.length() / 1_000_000} MB from $url", "AI")
                    return@withContext DownloadOutcome(true, "Local AI model ready. VOXYN now works offline.")
                }
                tmp.delete()
                VoxynLogger.e("Model file invalid (${downloadedTotal} bytes) from $url", tag = "AI")
            } catch (e: Exception) {
                tmp.delete()
                VoxynLogger.e("Model download failed from $url: ${e.message}", tag = "AI")
            }
        }

        DownloadOutcome(
            false,
            "Download failed. All mirrors unreachable — check your connection and try again."
        )
    }

    /** Loose validation: MediaPipe flatbuffer (TFL3), task zip (PK) or GGUF. */
    private fun isValidModel(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_SIZE) return false
        return runCatching {
            val head = file.inputStream().use { it.readNBytes(8) }
            head.size == 8 && (
                String(head, 4, 4, Charsets.US_ASCII) == "TFL3" ||
                String(head, 0, 2, Charsets.US_ASCII) == "PK" ||
                String(head, 0, 4, Charsets.US_ASCII) == "GGUF"
                )
        }.getOrDefault(false)
    }
}
