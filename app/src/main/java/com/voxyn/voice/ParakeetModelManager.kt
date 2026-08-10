package com.voxyn.voice

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.voxyn.ai.DownloadOutcome
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** User-facing state of the sherpa-onnx/Parakeet command STT model. */
data class AsrDownloadState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val modelPresent: Boolean = false
)

/**
 * ParakeetModelManager — downloads, validates, backs up and loads the
 * sherpa-onnx Parakeet-TDT 0.6B v3 INT8 command STT model.
 *
 * Model files live in the app-private dir filesDir/models/asr/ (never in Git and
 * never in the APK). A copy is also backed up to the public Downloads folder
 * (MediaStore, no permission needed) so uninstalling/reinstalling the app does
 * not require re-downloading ~670 MB.
 *
 * The OfflineRecognizer is created once and cached: it is ~650 MB resident, so
 * it is never reloaded per command. The VAD model (Silero, ~600 KB) is created
 * per listening session by the recognizer.
 */
class ParakeetModelManager(private val context: Context) {

    companion object {
        /** File name, verified download URL, and expected size from the model repo. */
        data class ModelFile(val name: String, val url: String, val expectedSize: Long)

        val MODEL_FILES = listOf(
            ModelFile(
                "encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx",
                652_184_281L
            ),
            ModelFile(
                "decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/decoder.int8.onnx",
                11_845_275L
            ),
            ModelFile(
                "joiner.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/joiner.int8.onnx",
                6_355_277L
            ),
            ModelFile(
                "tokens.txt",
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/tokens.txt",
                93_939L
            ),
            ModelFile(
                "silero_vad.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                643_854L
            )
        )

        const val BACKUP_DIR = "Download/VoxynAI/asr"
        private const val DOWNLOAD_DISPLAY_PREFIX = "voxyn-asr-"

        fun minSize(f: ModelFile): Long = (f.expectedSize * 95) / 100
    }

    private val _state = MutableStateFlow(AsrDownloadState(modelPresent = isModelPresent()))
    val downloadState: StateFlow<AsrDownloadState> = _state.asStateFlow()

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    // ─── Paths & presence ─────────────────────────────────────────────

    fun asrDir(): File = File(context.filesDir, "models/asr")

    fun file(name: String): File = File(asrDir(), name)

    fun isModelPresent(): Boolean =
        MODEL_FILES.all { f -> file(f.name).length() >= minSize(f) }

    // ─── Recognizer (load once, reuse) ────────────────────────────────

    /** Returns the cached recognizer, creating it on first use. Null if model missing/failed. */
    fun getRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!isModelPresent()) return null
        synchronized(lock) {
            recognizer?.let { return it }
            val rec = runCatching {
                val t = OfflineTransducerModelConfig().apply {
                    encoder = file("encoder.int8.onnx").absolutePath
                    decoder = file("decoder.int8.onnx").absolutePath
                    joiner = file("joiner.int8.onnx").absolutePath
                }
                val m = OfflineModelConfig().apply {
                    modelType = "nemo_transducer"
                    transducer = t
                    tokens = file("tokens.txt").absolutePath
                    numThreads = 2
                    provider = "cpu"
                    debug = false
                }
                val c = OfflineRecognizerConfig().apply {
                    featConfig = FeatureConfig().apply {
                        sampleRate = 16000
                        featureDim = 80
                    }
                    modelConfig = m
                    decodingMethod = "greedy_search"
                }
                VoxynLogger.i("ASR: Parakeet model loading", "ASR")
                val start = System.currentTimeMillis()
                val r = OfflineRecognizer(context.assets, c)
                VoxynLogger.i("ASR: Parakeet model ready (load = ${System.currentTimeMillis() - start} ms)", "ASR")
                r
            }.onFailure {
                VoxynLogger.e("ASR: Parakeet model load failed: ${it.message}", tag = "ASR")
            }.getOrNull()
            recognizer = rec
            return rec
        }
    }

    // ─── Download ─────────────────────────────────────────────────────

    /**
     * Downloads any missing model files (resumable) and reports 0..1 progress.
     * Safe to call repeatedly: finished files are skipped.
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): DownloadOutcome = withContext(Dispatchers.IO) {
        _state.update { it.copy(downloading = true, progress = 0f, message = "Downloading speech model (~670 MB, first time only)...") }

        val total = MODEL_FILES.sumOf { f -> f.expectedSize }
        var done = 0L
        var failed: String? = null

        for (f in MODEL_FILES) {
            if (isModelPresent()) break
            val target = file(f.name)
            if (target.length() >= minSize(f)) {
                done += f.expectedSize
                continue
            }
            val ok = downloadFileBlocking(f) { bytes ->
                val overall = ((done + bytes).toFloat() / total).coerceIn(0f, 1f)
                _state.update {
                    it.copy(progress = overall, message = "Downloading speech model ${(overall * 100).toInt()}%...")
                }
                onProgress(overall)
            }
            if (!ok) {
                failed = f.name
                break
            }
            done += f.expectedSize
        }

        val present = isModelPresent()
        if (present) {
            backupToDownloadsBlocking()
        }
        _state.update {
            it.copy(
                downloading = false,
                progress = if (present) 1f else 0f,
                modelPresent = present,
                message = when {
                    present -> "Speech model ready — Parakeet offline STT active."
                    failed != null -> "Speech model download failed ($failed). Check your connection and try again."
                    else -> "Speech model download failed. Check your connection and try again."
                }
            )
        }
        DownloadOutcome(present, _state.value.message)
    }

    /** Restores from Downloads when the private copy is missing (after a reinstall). */
    suspend fun ensureAvailable(): Boolean {
        if (isModelPresent()) {
            backupIfMissing()
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return withContext(Dispatchers.IO) {
            val restored = restoreFromDownloadsBlocking()
            if (restored) {
                VoxynLogger.i("ASR: Parakeet model restored from Downloads (no re-download)", "ASR")
                _state.update { it.copy(modelPresent = true, message = "Speech model restored from Downloads — ready.") }
            }
            restored
        }
    }

    /** Backs the model up to Downloads only when a valid copy is not there yet. */
    suspend fun backupIfMissing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isModelPresent()) return
        withContext(Dispatchers.IO) {
            for (f in MODEL_FILES) {
                if (!hasValidBackup(f.name)) backupFileBlocking(f.name)
            }
        }
    }

    // ─── Resumable download ───────────────────────────────────────────

    private fun downloadFileBlocking(f: ModelFile, onBytes: (Long) -> Unit): Boolean {
        val target = file(f.name)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${f.name}.part")
        val existing = part.length().takeIf { it < f.expectedSize } ?: 0L
        return try {
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                VoxynLogger.e("ASR: download HTTP $code for ${f.name}", tag = "ASR")
                return false
            }
            val append = code == 206 && existing > 0
            var bytes = existing
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        bytes += read
                        if (bytes % (512 * 1024) < read) onBytes(bytes)
                        else onBytes(bytes)
                    }
                }
            }
            if (part.length() >= minSize(f) && part.renameTo(target)) {
                VoxynLogger.i("ASR: downloaded ${f.name} (${part.length() / 1_000_000} MB)", "ASR")
                true
            } else {
                VoxynLogger.e("ASR: ${f.name} incomplete (${part.length()} bytes)", tag = "ASR")
                false
            }
        } catch (e: Exception) {
            VoxynLogger.e("ASR: download failed for ${f.name}: ${e.message}", tag = "ASR")
            false
        }
    }

    // ─── Downloads (MediaStore) backup / restore ──────────────────────

    private fun backupToDownloadsBlocking() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isModelPresent()) return
        for (f in MODEL_FILES) backupFileBlocking(f.name)
    }

    private fun backupFileBlocking(name: String) {
        runCatching {
            val resolver = context.contentResolver
            val uri = queryDownloadsUri(name) ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$DOWNLOAD_DISPLAY_PREFIX$name")
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, BACKUP_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            )
            if (uri == null) return@runCatching
            resolver.openOutputStream(uri)?.use { out ->
                file(name).inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }.onFailure {
            VoxynLogger.e("ASR: backup to Downloads failed for $name: ${it.message}", tag = "ASR")
        }
    }

    private fun restoreFromDownloadsBlocking(): Boolean = runCatching {
        asrDir().mkdirs()
        for (f in MODEL_FILES) {
            val target = file(f.name)
            if (target.length() >= minSize(f)) continue
            val uri = queryDownloadsUri(f.name) ?: return false
            val tmp = File(asrDir(), "${f.name}.restore")
            tmp.delete()
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out, 128 * 1024) }
                tmp.length() >= minSize(f)
            } ?: false
            if (!copied || !tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
        }
        isModelPresent()
    }.getOrDefault(false)

    private fun hasValidBackup(name: String): Boolean = runCatching {
        val uri = queryDownloadsUri(name) ?: return false
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.SIZE),
            null, null, null
        )?.use { c ->
            c.moveToFirst() && c.getLong(0) >= minSize(MODEL_FILES.first { it.name == name })
        } ?: false
    }.getOrDefault(false)

    private fun queryDownloadsUri(name: String): Uri? =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf("$DOWNLOAD_DISPLAY_PREFIX$name"),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            } else null
        }
}
