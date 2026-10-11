package com.voxyn.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val STATUS_FILE = "voxyn_diagnostics.log"
    private const val MAX_PRIVATE_LOG_BYTES = 2_000_000L

    @Volatile private var sinkReady = false
    @Volatile private var appContext: Context? = null
    @Volatile private var fileSink: Uri? = null
    @Volatile private var directFileSink: File? = null
    @Volatile private var fileSinkAttempted = false
    @Volatile private var lastFileSinkAttemptMs = 0L
    private val workerStarted = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voxyn-log-sink").apply { isDaemon = true }
    }
    private val queue = ArrayBlockingQueue<String>(512)

    fun init() {
        // NOTE: initFileSink(context) must be called separately after a Context is available.
        sinkReady = true
        if (!workerStarted.compareAndSet(false, true)) return
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val pending = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    val line = "${timestamp()} $pending"
                    ensureFileSink()
                    appendToPrivateFile(line)
                    appendToFile(line)
                    post(line)
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    Log.e(DEFAULT_TAG, "Diagnostic log worker failed", error)
                }
            }
        }
    }

    /**
     * Writes a status log to the public Downloads folder via MediaStore so it can
     * be read back from Termux for on-device diagnostics (no permissions needed).
     */
    fun initFileSink(context: Context) {
        // Only retain the application context here. MediaStore can block on provider startup,
        // so all resolver work is deferred to the logger's background worker.
        appContext = context.applicationContext
        // Force an initial record immediately so the file exists in Downloads
        // even before the first service or screen emits a log line.
        enqueue("I VOXYN: Diagnostics logging started; file=Download/$STATUS_FILE")
    }

    private fun ensureFileSink() {
        if (fileSink != null || directFileSink != null) return
        val now = System.currentTimeMillis()
        if (fileSinkAttempted && now - lastFileSinkAttemptMs < 10_000L) return
        val context = appContext ?: return
        fileSinkAttempted = true
        lastFileSinkAttemptMs = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            // On Android 11+, use a normal file in shared Downloads when the
            // user has granted the app's already-declared All files access.
            // This also makes the log readable from Termux and file managers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    STATUS_FILE
                )
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
                if (file.isFile && file.canWrite()) {
                    directFileSink = file
                    Log.i(DEFAULT_TAG, "Persistent diagnostics sink ready: ${file.absolutePath}")
                    return
                }
                Log.w(DEFAULT_TAG, "All files access reported granted, but Downloads log is not writable")
            }

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
            if (uri == null) {
                Log.e(DEFAULT_TAG, "Persistent Downloads log could not be created: insert returned null")
            } else {
                // Confirm the provider can open the item before reporting success.
                resolver.openOutputStream(uri!!, "wa")?.close()
                    ?: throw IllegalStateException("Downloads provider returned no output stream")
                Log.i(DEFAULT_TAG, "Persistent diagnostics sink ready: Download/$STATUS_FILE")
            }
        } catch (error: Exception) {
            fileSink = null
            // Logging must never interfere with app startup, even when MediaStore is unavailable.
            Log.e(DEFAULT_TAG, "Persistent Downloads log unavailable; retaining private diagnostic log", error)
        }
    }

    fun d(message: String, tag: String = DEFAULT_TAG) = Log.d(tag, message).also { sink("D", tag, message) }
    fun i(message: String, tag: String = DEFAULT_TAG) = Log.i(tag, message).also { sink("I", tag, message) }
    fun w(message: String, tag: String = DEFAULT_TAG) = Log.w(tag, message).also { sink("W", tag, message) }
    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) =
        Log.e(tag, message, throwable).also { sink("E", tag, message) }

    /** Queue crash lines without doing disk or network I/O on the crashing thread. */
    fun crash(message: String) {
        Log.e("FATAL", message)
        val line = "${timestamp()} FATAL VOXYN: $message"
        // The async queue may be terminated with the crashing process. Write
        // the crash record immediately to both private and shared storage.
        ensureFileSink()
        appendToPrivateFile(line)
        appendToFile(line)
    }

    fun engine(message: String) = i(message, "VOXYN::ENGINE")
    fun phase(phase: String, message: String) = d(message, "VOXYN::$phase")

    private fun sink(level: String, tag: String, message: String) {
        if (!sinkReady) return
        enqueue("$level $tag: $message")
    }

    private fun enqueue(line: String) {
        if (!sinkReady) return
        if (!queue.offer(line)) {
            queue.poll()
            queue.offer(line)
        }
    }

    private fun appendToFile(line: String) {
        val directFile = directFileSink
        if (directFile != null) {
            try {
                FileOutputStream(directFile, true).buffered().use { out ->
                    out.write("$line\n".toByteArray(Charsets.UTF_8))
                }
                return
            } catch (error: Exception) {
                directFileSink = null
                Log.e(DEFAULT_TAG, "Could not append shared Downloads log; will retry", error)
            }
        }
        val uri = fileSink ?: return
        val context = appContext ?: return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                out.write("$line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (error: Exception) {
            fileSink = null
            Log.e(DEFAULT_TAG, "Could not append persistent Downloads log; will retry", error)
        }
    }

    /** Durable private fallback plus a stable shared Downloads copy for support diagnostics. */
    private fun appendToPrivateFile(line: String) {
        val context = appContext ?: return
        runCatching {
            val directory = File(context.noBackupFilesDir, "diagnostics")
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, STATUS_FILE)
            if (file.length() >= MAX_PRIVATE_LOG_BYTES) {
                val previous = File(directory, "$STATUS_FILE.1")
                previous.delete()
                file.renameTo(previous)
            }
            FileOutputStream(file, true).buffered().use { out ->
                out.write("$line\n".toByteArray(Charsets.UTF_8))
            }
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

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
