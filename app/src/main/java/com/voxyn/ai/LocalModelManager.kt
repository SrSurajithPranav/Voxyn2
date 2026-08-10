package com.voxyn.ai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
 * (MediaPipe .task / flatbuffer model).
 *
 * The model is stored in the app's private filesDir, but a copy is ALSO backed
 * up to the public Downloads folder (MediaStore, no permission needed). That way
 * uninstalling/reinstalling the app does NOT lose the ~1.4 GB model — after a
 * reinstall VOXYN restores it from Downloads instead of re-downloading.
 */
class LocalModelManager(private val context: Context) {

    companion object {
        // Gemma 2B int4 (GPU delegate). If one mirror dies, the next is tried.
        val MODEL_URLS = listOf(
            "https://huggingface.co/vba01/gemma-2b-it-gpu-int4/resolve/main/gemma-2b-it-gpu-int4.bin",
            "https://huggingface.co/autoocrat0413/gemma-2b-it-gpu-int4-mediapipe/resolve/main/gemma-2b-it-gpu-int4.bin",
            "https://huggingface.co/autoocrat0413/gemma-2b-it-gpu-int4-mediapipe/resolve/main/gemma-2b-it-cpu-int4.bin"
        )
        const val MODEL_FILE_NAME   = "llm.task"
        const val DOWNLOAD_FILE_NAME = "voxyn-llm.task"
        const val DOWNLOAD_DIR       = "Download/VoxynAI"
        private const val MIN_VALID_SIZE = 1_000_000L
    }

    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isModelPresent(): Boolean =
        modelFile.exists() && isValidModel(modelFile)

    /** Private copy missing but a backup exists in Downloads → copy it back. */
    suspend fun ensureModelAvailable(): Boolean {
        if (isModelPresent()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return withContext(Dispatchers.IO) {
            val restored = restoreFromDownloadsBlocking()
            if (restored) {
                VoxynLogger.i("Local LLM model restored from Downloads (no re-download)", "AI")
            }
            restored
        }
    }

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
                    backupToDownloadsBlocking()
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

    suspend fun backupToDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        withContext(Dispatchers.IO) { backupToDownloadsBlocking() }
    }

    /**
     * Backs the model up to Downloads only when no valid copy exists there yet.
     * Used on app start when the model is already on device (e.g. after upgrading
     * from a build that didn't create the backup) — avoids copying ~1.4 GB every launch.
     */
    suspend fun backupIfMissing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        withContext(Dispatchers.IO) {
            if (hasValidBackup()) return@withContext
            backupToDownloadsBlocking()
        }
    }

    // ─── Downloads (MediaStore) backup/restore ─────────────────────────

    private fun backupToDownloadsBlocking() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isModelPresent()) return
        runCatching {
            val resolver = context.contentResolver
            var uri = queryDownloadsUri() ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, DOWNLOAD_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            )
            if (uri == null) return@runCatching
            resolver.openOutputStream(uri)?.use { out ->
                modelFile.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            VoxynLogger.i("Local LLM model backed up to Downloads/VoxynAI", "AI")
        }.onFailure {
            VoxynLogger.e("Model backup to Downloads failed: ${it.message}", tag = "AI")
        }
    }

    private fun restoreFromDownloadsBlocking(): Boolean = runCatching {
        val resolver = context.contentResolver
        val uri = queryDownloadsUri() ?: return false
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.restore")
        tmp.delete()
        val copied = resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 128 * 1024) }
            tmp.length() > MIN_VALID_SIZE
        } ?: false
        if (copied && isValidModel(tmp) && tmp.renameTo(modelFile)) {
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    /** True when a valid backup already exists in Downloads (size check only). */
    private fun hasValidBackup(): Boolean =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(DOWNLOAD_FILE_NAME),
            null
        )?.use { c ->
            c.moveToFirst() && c.getLong(0) >= MIN_VALID_SIZE
        } ?: false

    private fun queryDownloadsUri(): Uri? =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(DOWNLOAD_FILE_NAME),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            } else null
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
