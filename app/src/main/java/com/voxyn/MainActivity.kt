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
import android.widget.Toast
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

    private var micRequestPending = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            VoxynLogger.i("Permission $perm: $granted", "MAIN")
        }
        if (micRequestPending) {
            micRequestPending = false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startVoiceInput()
            } else {
                Toast.makeText(this, "Microphone permission is required — open Settings to allow it", Toast.LENGTH_LONG).show()
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            startVoxynServices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoxynTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VoxynBg
                ) {
                    MainScreen(onStartVoiceInput = ::startVoiceInput)
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
        // If the user just granted mic in Settings, start the wake service now
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoxynServices()
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            VoxynLogger.w("Microphone permission is required before starting voice services", "MAIN")
            return
        }
        startForegroundService(Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        })
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micRequestPending = true
            requestPermissions()
            return
        }
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START_LISTENING
                }
            )
            VoxynLogger.i("Mic button pressed — sent ACTION_START_LISTENING", "MAIN")
        } catch (e: Exception) {
            VoxynLogger.e("Voice input start failed: ${e.message}", tag = "MAIN")
            Toast.makeText(this, "Couldn't start voice: ${e.message}", Toast.LENGTH_LONG).show()
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
