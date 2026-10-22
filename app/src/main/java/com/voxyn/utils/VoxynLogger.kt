package com.voxyn.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Logs to logcat and, when a debug listener is running on the device
 * (Termux: python http server on 127.0.0.1:19090), streams every line
 * there too so runtime behavior can be inspected without root/adb.
 */
object VoxynLogger {
    private const val DEFAULT_TAG = "VOXYN"
    private const val SINK_URL = "http://127.0.0.1:19090/voxyn"
    private const val STATUS_FILE = "voxyn_status.log"

    @Volatile private var sinkReady = false
    @Volatile private var appContext: Context? = null
    @Volatile private var fileSink: Uri? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voxyn-log-sink").apply { isDaemon = true }
    }
    private val queue = ArrayBlockingQueue<String>(512)

    fun init() {
        // NOTE: initFileSink(context) must be called separately after a Context is available.
        sinkReady = true
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val line = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    post(line)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Writes a status log to the public Downloads folder via MediaStore so it can
     * be read back from Termux for on-device diagnostics (no permissions needed).
     */
    fun initFileSink(context: Context) {
        // Guard: skip if already initialised (prevents double MediaStore insert on cold start).
        if (fileSink != null) return
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            var uri: Uri? = null
            // Look for an existing MediaStore entry first so we never call insert() twice.
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(STATUS_FILE),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0)
                    )
                }
            }
            if (uri == null) {
                uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, STATUS_FILE)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                )
            }
            fileSink = uri
        } catch (_: Exception) {
        }
    }

    fun d(message: String, tag: String = DEFAULT_TAG) = Log.d(tag, message).also { sink("D", tag, message) }
    fun i(message: String, tag: String = DEFAULT_TAG) = Log.i(tag, message).also { sink("I", tag, message) }
    fun w(message: String, tag: String = DEFAULT_TAG) = Log.w(tag, message).also { sink("W", tag, message) }
    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) =
        Log.e(tag, message, throwable).also { sink("E", tag, message) }

    /** Synchronous post — used for crash lines that must not be lost. */
    fun crash(message: String) {
        Log.e("FATAL", message)
        appendToFile("FATAL", "VOXYN", message)
        post("FATAL: $message")
    }

    fun engine(message: String) = i(message, "VOXYN::ENGINE")
    fun phase(phase: String, message: String) = d(message, "VOXYN::$phase")

    private fun sink(level: String, tag: String, message: String) {
        appendToFile(level, tag, message)
        if (!sinkReady) return
        val line = "$level $tag: $message"
        if (!queue.offer(line)) {
            queue.poll()
            queue.offer(line)
        }
    }

    private fun appendToFile(level: String, tag: String, message: String) {
        val uri = fileSink ?: return
        val context = appContext ?: return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                out.write("$level $tag: $message\n".toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    private fun post(line: String) {
        try {
            val conn = URL(SINK_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.setRequestProperty("Content-Type", "text/plain")
            val out: OutputStream = conn.outputStream
            try {
                out.write(line.toByteArray(Charsets.UTF_8))
            } finally {
                out.close()
            }
            conn.inputStream.close()
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
