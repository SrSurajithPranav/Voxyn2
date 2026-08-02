package com.voxyn

import android.app.Application
import android.content.Intent
import com.voxyn.di.appModule
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
            VoxynLogger.e("Failed to start services: ${e.message}", tag = "APP")
        }
    }
}
