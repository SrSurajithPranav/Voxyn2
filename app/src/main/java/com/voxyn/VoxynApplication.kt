package com.voxyn

import android.app.Application
import android.app.ActivityManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.ai.LocalModelManager
import com.voxyn.di.appModule
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.ParakeetModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoxynApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        VoxynLogger.init()
        VoxynLogger.initFileSink(this)
        installCrashHandler()

        startKoin {
            androidContext(this@VoxynApplication)
            modules(appModule)
        }

        logDiagnostics()
        logPreviousProcessExits()
        VoxynLogger.i("VOXYN starting — background model restoration scheduled", "APP")
        VoxynLogger.i("Microphone service starts when the app is visible", "APP")
        restoreModelsInBackground()
    }

    /**
     * Restores the on-device models (Parakeet ASR + local LLM) from the public
     * Downloads backup so a reinstall never requires re-downloading ~1.2 GB.
     * The LLM manager falls back to a background download when no valid backup
     * exists, so VOXYN becomes fully offline-capable without touching the UI.
     */
    private fun restoreModelsInBackground() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val parakeetManager = get<ParakeetModelManager>()
            val asrOk = runCatching {
                parakeetManager.ensureAvailable()
            }.getOrElse {
                VoxynLogger.e("ASR model restore failed: ${it.message}", tag = "APP")
                false
            }
            VoxynLogger.i(
                if (asrOk) "ASR Parakeet model ready — offline STT active"
                else "ASR model not available — Vosk fallback until download completes",
                "APP"
            )
            if (asrOk) {
                // Pre-load the ~650 MB recognizer in the background so the first
                // Spoken commands are available as soon as the continuous listener starts.
                val t0 = System.currentTimeMillis()
                val rec = runCatching { parakeetManager.getRecognizer() }.getOrNull()
                VoxynLogger.i(
                    if (rec != null) "ASR Parakeet recognizer pre-loaded (${System.currentTimeMillis() - t0} ms)"
                    else "ASR Parakeet recognizer pre-load failed",
                    "APP"
                )
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val llmOk = runCatching {
                get<LocalModelManager>().ensureModelAvailable()
            }.getOrElse {
                VoxynLogger.e("Local LLM model restore failed: ${it.message}", tag = "APP")
                false
            }
            VoxynLogger.i(
                if (llmOk) "Local LLM model ready — offline AI active"
                else "Local LLM model not available yet",
                "APP"
            )
        }
    }

    private fun logDiagnostics() {
        val mic = (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
        VoxynLogger.i(
            "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "micGranted=$mic notificationsGranted=$notif overlay=${Settings.canDrawOverlays(this)}",
            "APP"
        )
    }

    /** Android keeps crash and ANR details for our own process even if the process
     * died before VoxynLogger could flush its fatal line. Copy recent records into
     * the Termux/file log on the next launch. */
    private fun logPreviousProcessExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)
                .take(3)
                .forEach { exit ->
                    VoxynLogger.w(
                        "Previous process exit reason=${exit.reason} status=${exit.status} " +
                            "importance=${exit.importance} timestamp=${exit.timestamp} " +
                            "description=${exit.description}",
                        "CRASH"
                    )
                    if (exit.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                        exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        exit.reason == android.app.ApplicationExitInfo.REASON_ANR
                    ) {
                        val trace = exit.traceInputStream?.bufferedReader()?.use { reader ->
                            reader.readText().take(32_000)
                        }
                        if (!trace.isNullOrBlank()) {
                            VoxynLogger.e("Previous process exit trace:\n$trace", tag = "CRASH")
                        }
                    }
                }
        }.onFailure { error ->
            VoxynLogger.w("Could not read previous process exit details: ${error.message}", "CRASH")
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            VoxynLogger.crash("Uncaught on ${thread.name}", throwable)
            prev?.uncaughtException(thread, throwable)
        }
    }

}
