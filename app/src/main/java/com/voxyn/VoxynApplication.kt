package com.voxyn

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.di.appModule
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService
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
