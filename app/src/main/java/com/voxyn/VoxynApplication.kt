package com.voxyn

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.ai.LocalModelManager
import com.voxyn.di.appModule
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.ParakeetModelManager
import com.voxyn.wake.WakeWordService
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
        VoxynLogger.i("VOXYN starting — launching background services", "APP")
        startWakeService()
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
        }
        // Pre-load the ~650 MB recognizer in the background so the first
        // "Hey VOXYN" command is captured from the very first word.
        val parakeetManager = get<ParakeetModelManager>()
        val recT0 = runCatching {
            parakeetManager.getRecognizer()
        }.getOrNull()
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
        VoxynLogger.i(
            if (recT0 != null) "ASR Parakeet recognizer pre-loaded (${System.currentTimeMillis() - recT0} ms)"
            else "ASR Parakeet recognizer pre-load failed",
            "APP"
        )
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

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            VoxynLogger.crash("Uncaught on ${thread.name}: $throwable")
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun startWakeService() {
        // Never start the always-on listener without the mic — otherwise it shows
        // a "listening" notification while hearing nothing.
        val micOk = (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED)
        if (!micOk) {
            VoxynLogger.w("Microphone permission not granted — wake word service NOT started", "APP")
            return
        }
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
            VoxynLogger.i("WakeWordService started from Application", "APP")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start WakeWordService: ${e.message}", tag = "APP")
        }
    }
}
