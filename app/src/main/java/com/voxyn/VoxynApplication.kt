package com.voxyn

import android.app.Application
import com.voxyn.di.appModule
import com.voxyn.utils.VoxynLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoxynApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VoxynApplication)
            modules(appModule)
        }
        // Runtime permissions belong to an Activity. Starting microphone services
        // here can violate Android 14 foreground-service permission rules.
        VoxynLogger.i("VOXYN initialized", "APP")
    }
}
