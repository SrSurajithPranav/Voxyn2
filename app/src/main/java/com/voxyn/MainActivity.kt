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
import com.voxyn.overlay.OverlayService
import com.voxyn.ui.MainScreen
import com.voxyn.ui.theme.VoxynBg
import com.voxyn.ui.theme.VoxynTheme
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService

class MainActivity : ComponentActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

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

    override fun onResume() {
        super.onResume()
        // Check overlay permission every time app comes to foreground
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
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

        // Always request overlay permission — it's needed for DynamicIsland
        if (!Settings.canDrawOverlays(this)) {
            VoxynLogger.w("Requesting overlay permission for DynamicIsland", "MAIN")
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
        } else {
            startOverlayService()
        }
    }

    private fun startVoxynServices() {
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
            VoxynLogger.i("WakeWordService started", "MAIN")
        } catch (e: Exception) {
            VoxynLogger.e("Service start failed: ${e.message}", tag = "MAIN")
        }
    }

    private fun startOverlayService() {
        try {
            startForegroundService(
                Intent(this, OverlayService::class.java)
            )
            VoxynLogger.i("OverlayService started", "MAIN")
        } catch (e: Exception) {
            VoxynLogger.e("OverlayService start failed: ${e.message}", tag = "MAIN")
        }
    }
}
