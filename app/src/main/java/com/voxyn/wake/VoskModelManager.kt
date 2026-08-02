package com.voxyn.wake

import android.content.Context
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed interface VoskModelState {
    data object Uninitialized : VoskModelState
    data class Downloading(val progress: Float) : VoskModelState
    data class Extracting(val progress: Float) : VoskModelState
    data class Ready(val modelPath: String) : VoskModelState
    data class Error(val message: String) : VoskModelState
}

class VoskModelManager(private val context: Context) {

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_ARCHIVE = "vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _modelState = MutableStateFlow<VoskModelState>(VoskModelState.Uninitialized)
    val modelState: StateFlow<VoskModelState> = _modelState.asStateFlow()

    @Volatile
    private var isWorking = false

    fun ensureModelAvailable() {
        val existing = resolveExtractedModelDir()
        if (existing != null) {
            _modelState.value = VoskModelState.Ready(existing.absolutePath)
            return
        }

        if (isWorking) return
        isWorking = true

        scope.launch {
            runCatching {
                val archive = downloadModelArchive()
                extractArchive(archive)
                val readyDir = resolveExtractedModelDir()
                    ?: error("Model extracted but not found")
                _modelState.value = VoskModelState.Ready(readyDir.absolutePath)
                VoxynLogger.i("Vosk model ready: ${readyDir.absolutePath}", "VOSK")
            }.onFailure { err ->
                VoxynLogger.e("Vosk model setup failed: ${err.message}", tag = "VOSK")
                _modelState.value = VoskModelState.Error(err.message ?: "Model setup failed")
            }
            isWorking = false
        }
    }

    fun retryModelSetup() {
        if (isWorking) return
        _modelState.value = VoskModelState.Uninitialized
        runCatching {
            modelBaseDir().deleteRecursively()
        }
        ensureModelAvailable()
    }

    private fun modelBaseDir(): File = File(context.filesDir, "vosk/models")

    private fun resolveExtractedModelDir(): File? {
        val direct = File(modelBaseDir(), MODEL_DIR_NAME)
        if (direct.exists() && File(direct, "am").exists()) return direct

        val nested = File(modelBaseDir(), MODEL_DIR_NAME).walkTopDown()
            .firstOrNull { it.isDirectory && File(it, "am").exists() }
        return nested
    }

    private fun downloadModelArchive(): File {
        val baseDir = modelBaseDir().apply { mkdirs() }
        val target = File(baseDir, MODEL_ARCHIVE)

        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
        }

        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed with HTTP ${conn.responseCode}")
        }

        val contentLen = conn.contentLengthLong.coerceAtLeast(1L)
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8 * 1024)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    _modelState.value = VoskModelState.Downloading(
                        (downloaded.toFloat() / contentLen.toFloat()).coerceIn(0f, 1f)
                    )
                }
            }
        }

        return target
    }

    private fun extractArchive(archive: File) {
        val baseDir = modelBaseDir().apply { mkdirs() }

        val totalEntries = runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var count = 0
                while (zip.nextEntry != null) {
                    count++
                }
                count.coerceAtLeast(1)
            }
        }.getOrDefault(1)

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var processed = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                processed++
                val outFile = File(baseDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                _modelState.value = VoskModelState.Extracting(
                    (processed.toFloat() / totalEntries.toFloat()).coerceIn(0f, 1f)
                )
            }
        }
    }
}
