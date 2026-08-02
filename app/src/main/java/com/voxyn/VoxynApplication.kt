package com.voxyn

import android.app.Application
import android.content.Intent
import android.provider.Settings
import com.voxyn.di.appModule
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoxynApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VoxynApplication)
            modules(appModule)
        }
        VoxynLogger.i("VOXYN starting", "APP")
        startServices()
    }

    private fun startServices() {
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
            VoxynLogger.i("WakeWordService launched", "APP")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start WakeWordService: ${e.message}", tag = "APP")
        }

        // Only start OverlayService if overlay permission is granted
        if (Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(Intent(this, OverlayService::class.java))
                VoxynLogger.i("OverlayService launched", "APP")
            } catch (e: Exception) {
                VoxynLogger.e("Failed to start OverlayService: ${e.message}", tag = "APP")
            }
        } else {
            VoxynLogger.w("Overlay permission missing — OverlayService not started yet", "APP")
        }
    }
}
