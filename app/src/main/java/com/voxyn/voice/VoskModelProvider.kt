package com.voxyn.voice

import android.content.Context
import com.voxyn.utils.VoxynLogger
import org.vosk.Model
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Loads the bundled offline Vosk model (assets/model-en-us) once and shares it
 * between the wake-word service and the command-listening service. The model is
 * extracted manually because StorageService.sync fails on some devices with
 * "model-en-us/uuid".
 */
class VoskModelProvider(private val context: Context) {

    companion object {
        private const val ASSET_DIR = "model-en-us"
        private const val TARGET_DIR = "model"
    }

    @Volatile private var cachedModel: Model? = null

    @Synchronized
    fun getModel(): Model? {
        cachedModel?.let { return it }
        return try {
            val path = ensureExtracted()
            val model = Model(path)
            cachedModel = model
            VoxynLogger.i("Vosk model loaded from $path", "VOSK")
            model
        } catch (e: Exception) {
            VoxynLogger.e("Vosk model load failed: ${e.message}", tag = "VOSK")
            null
        }
    }

    @Synchronized
    fun isExtracted(): Boolean = File(modelDir(), "am/final.mdl").exists()

    @Synchronized
    fun invalidateModel() {
        try { cachedModel?.close() } catch (_: Exception) {}
        cachedModel = null
    }

    private fun modelDir(): File = File(context.filesDir, TARGET_DIR)

    private fun ensureExtracted(): String {
        val dir = modelDir()
        if (File(dir, "am/final.mdl").exists()) return dir.absolutePath

        VoxynLogger.i("Extracting bundled Vosk model from assets", "VOSK")
        dir.deleteRecursively()
        if (!dir.mkdirs()) {
            throw IllegalStateException("Cannot create model directory")
        }
        copyAssetTree(ASSET_DIR, dir)
        if (!File(dir, "am/final.mdl").exists()) {
            throw IllegalStateException("Model extraction incomplete")
        }
        VoxynLogger.i("Vosk model extracted to ${dir.absolutePath}", "VOSK")
        return dir.absolutePath
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val assets = context.assets
        try {
            assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
            return
        } catch (_: FileNotFoundException) {
            // Not a file — treat as a directory below.
        }

        target.mkdirs()
        val children = assets.list(assetPath) ?: return
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }
}
