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
        logHistoricalProcessExits()
        VoxynLogger.i("VOXYN starting — microphone service awaits a visible user entry point", "APP")
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
                if (asrOk) "Parakeet files available; command runtime uses low-memory Vosk"
                else "Parakeet unavailable; bundled low-memory Vosk command STT active",
                "APP"
            )
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
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        VoxynLogger.i(
            "memory availableMiB=${memory.availMem / (1024 * 1024)} totalMiB=${memory.totalMem / (1024 * 1024)} lowMemory=${memory.lowMemory}",
            "APP"
        )
    }

    /** Android retains the reason for recent process deaths, including LMK/native exits. */
    private fun logHistoricalProcessExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            manager.getHistoricalProcessExitReasons(packageName, 0, 5).forEach { exit ->
                VoxynLogger.w(
                    "Previous process exit: reason=${exit.reason} status=${exit.status} " +
                        "timestamp=${exit.timestamp} process=${exit.processName} " +
                        "description=${exit.description.orEmpty().take(300)}",
                    "CRASH"
                )
            }
        }.onFailure {
            VoxynLogger.w("Could not read historical process exits: ${it.message}", "CRASH")
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            VoxynLogger.crash("Uncaught on ${thread.name}: ${android.util.Log.getStackTraceString(throwable)}")
            prev?.uncaughtException(thread, throwable)
        }
    }

}
