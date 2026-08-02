package com.voxyn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.voxyn.ui.MainScreen
import com.voxyn.ui.theme.VoxynBg
import com.voxyn.ui.theme.VoxynTheme
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            VoxynLogger.i("Permission $perm: $granted", "MAIN")
        }
        startVoxynServices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoxynTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VoxynBg
                ) {
                    MainScreen()
                }
            }
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startVoxynServices()
        }

        // Request overlay permission separately (needs special flow)
        if (!Settings.canDrawOverlays(this)) {
            VoxynLogger.w("Overlay permission not granted — ask user", "MAIN")
        }
    }

    private fun startVoxynServices() {
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
        } catch (e: Exception) {
            VoxynLogger.e("Service start failed: ${e.message}", tag = "MAIN")
        }
    }
}
