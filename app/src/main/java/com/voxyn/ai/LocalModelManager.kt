package com.voxyn.ai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
 * uninstalling/reinstalling the app does NOT lose the ~0.6 GB model — after a
 * reinstall VOXYN restores it from Downloads instead of re-downloading.
 */
class LocalModelManager(private val context: Context) {

    companion object {
        // Gemma 3 1B int4 — a real MediaPipe .task bundle (zip). The earlier mirrors
        // served raw TFLite .bin files, which MediaPipe GenAI rejects with
        // "Unable to open zip archive". If one mirror dies, the next is tried.
        val MODEL_URLS = listOf(
            "https://huggingface.co/JafarKhan1989/gemma-3-1b-mediapipe/resolve/main/gemma3-1B-it-int4.task",
            "https://huggingface.co/gummybear2555/Gemma-3n-E2B-it-int4/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task"
        )
        const val MODEL_FILE_NAME   = "llm.task"
        const val DOWNLOAD_FILE_NAME = "voxyn-llm.task"
        const val DOWNLOAD_DIR       = "Download/VoxynAI"
        private const val MIN_VALID_SIZE = 1_000_000L
    }

    val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    @Volatile private var downloadInProgress = false

    fun isModelPresent(): Boolean =
        modelFile.exists() && isValidModel(modelFile)

    /**
     * Makes the LLM available without user interaction: verifies the private
     * copy, restores it from Downloads after a reinstall, and as a last resort
     * downloads it in the background so the app is never left without AI.
     * Pass [allowDownload] = false when the caller only wants the instant
     * local restore (Downloads / raw storage) path.
     */
    suspend fun ensureModelAvailable(allowDownload: Boolean = true): Boolean {
        if (isModelPresent()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val restored = withContext(Dispatchers.IO) {
                restoreFromDownloadsBlocking() || restoreFromRawPathBlocking()
            }
            if (restored) {
                VoxynLogger.i("Local LLM model restored from Downloads (no re-download)", "AI")
                return true
            }
        } else if (withContext(Dispatchers.IO) { restoreFromRawPathBlocking() }) {
            VoxynLogger.i("Local LLM model restored from shared storage (no re-download)", "AI")
            return true
        }
        if (!allowDownload) return false
        VoxynLogger.i("Local LLM model not on device — downloading in background", "AI")
        return downloadModel {}.ok
    }

    /** True when a valid backup row is visible in the MediaStore Downloads collection. */
    fun hasMediaStoreBackup(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        queryDownloadsUri() != null
    }.getOrDefault(false)

    /** Streams the model to filesDir; reports 0..1 progress for the active mirror. */
    suspend fun downloadModel(onProgress: (Float) -> Unit): DownloadOutcome = withContext(Dispatchers.IO) {
        if (downloadInProgress) {
            return@withContext DownloadOutcome(false, "Download already in progress")
        }
        downloadInProgress = true
        try {
            modelFile.parentFile?.mkdirs()

            val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.part")
            for (url in MODEL_URLS) {
                if (tmp.length() >= MIN_VALID_SIZE) {
                    // A full-size .part that never got validated is corrupt — discard it.
                    tmp.delete()
                }
                var downloadedTotal = if (tmp.exists()) tmp.length() else 0L
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
                        if (downloadedTotal > 0 && downloadedTotal < MIN_VALID_SIZE) {
                            setRequestProperty("Range", "bytes=$downloadedTotal-")
                        }
                    }
                    val code = conn.responseCode
                    if (code != 200 && code != 206) {
                        VoxynLogger.e("Model download HTTP $code from $url", tag = "AI")
                        continue
                    }

                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp, code == 206 && downloadedTotal > 0).use { out ->
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
                    VoxynLogger.e("Model download failed from $url: ${e.message}", tag = "AI")
                }
            }

            DownloadOutcome(
                false,
                "Download failed. All mirrors unreachable — check your connection and try again."
            )
        } finally {
            downloadInProgress = false
        }
    }

    suspend fun backupToDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        withContext(Dispatchers.IO) { backupToDownloadsBlocking() }
    }

    /**
     * Backs the model up to Downloads only when no valid copy exists there yet.
     * Used on app start when the model is already on device (e.g. after upgrading
     * from a build that didn't create the backup) — avoids copying ~0.6 GB every launch.
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

    /** Direct-storage fallback: Android 11+ needs "All files access" to read free files. */
    private fun restoreFromRawPathBlocking(): Boolean = runCatching {
        val backup = rawBackupFile() ?: return false
        if (!backup.exists() || !backup.canRead() || backup.length() < MIN_VALID_SIZE) return false
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.restore")
        tmp.delete()
        val copied = backup.inputStream().use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 128 * 1024) }
            tmp.length() > MIN_VALID_SIZE
        }
        if (copied && isValidModel(tmp) && tmp.renameTo(modelFile)) {
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    private fun rawBackupFile(): File? {
        val candidates = listOf(
            File(Environment.getExternalStorageDirectory(), "$DOWNLOAD_DIR/$DOWNLOAD_FILE_NAME"),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VoxynAI/$DOWNLOAD_FILE_NAME"
            )
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    /** True when a valid .task backup already exists in Downloads. */
    private fun hasValidBackup(): Boolean = runCatching {
        val uri = queryDownloadsUri() ?: return false
        val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return false
        fd.use { afd ->
            val length = afd.length
            if (length < MIN_VALID_SIZE) return false
            val input = java.io.FileInputStream(afd.fileDescriptor)
            input.use { ins ->
                val head = ByteArray(8)
                var h = 0
                while (h < 8) {
                    val n = ins.read(head, h, 8 - h)
                    if (n < 0) break
                    h += n
                }
                (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) ||
                    (head.size >= 6 && head[4] == 0x50.toByte() && head[5] == 0x4B.toByte())
            }
        }
    }.getOrDefault(false)

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

    private fun isValidModel(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_SIZE) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().hasMoreElements()
            }
        }.getOrDefault(false)
    }
}
