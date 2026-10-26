package com.voxyn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.voxyn.ui.VoxynViewModel
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    private val uiViewModel: VoxynViewModel by viewModel()
    private var micRequestPending = false
    private var storagePromptShown = false

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
        }
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoxynTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VoxynBg
                ) {
                    MainScreen(
                        onStartVoiceInput = ::startVoiceInput,
                        onRequestAllFilesAccess = ::requestAllFilesAccess
                    )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            // The user just granted All-files access — restore instantly.
            uiViewModel.restoreModelsFromStorage()
        }
        maybePromptForStorageAccess()
    }

    private fun maybePromptForStorageAccess() {
        if (storagePromptShown) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (Environment.isExternalStorageManager()) return
        if (uiViewModel.modelsPresent()) return
        if (uiViewModel.hasDownloadsBackup()) return
        storagePromptShown = true
        VoxynLogger.w("Model backup present on device but hidden — prompting for All-files access", "MAIN")
        uiViewModel.showStoragePrompt(true)
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                VoxynLogger.e("All-files access settings failed: ${e.message}", tag = "MAIN")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                }
            }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
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
            // Microphone foreground services must start only after this Activity
            // is visible. onResume owns that transition; starting here in onCreate
            // can race Android's foreground-service eligibility checks.
            checkOverlayPermission()
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                VoxynLogger.i("Prompted user to ignore battery optimizations for 24x7 operation", "MAIN")
            } catch (e: Exception) {
                VoxynLogger.w("Could not launch ignore battery optimizations intent: ${e.message}", "MAIN")
            }
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            VoxynLogger.w("Requesting overlay permission for DynamicIsland", "MAIN")
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            } catch (e: Exception) {
                VoxynLogger.w("Could not open overlay settings: ${e.message}", "MAIN")
            }
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
        try {
            startForegroundService(Intent(this, VoiceInputService::class.java).apply {
                action = VoiceInputService.ACTION_START_LISTENING
            })
            VoxynLogger.i("VoiceInputService started from MainActivity (Always-Listening Mode)", "MAIN")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start VoiceInputService: ${e.message}", tag = "MAIN")
        }
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
                Intent(this, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                }
            )
            VoxynLogger.i("Mic button pressed — sent ACTION_START_LISTENING to VoiceInputService", "MAIN")
        } catch (e: Exception) {
            VoxynLogger.e("Voice input start failed: ${e.message}", tag = "MAIN")
            Toast.makeText(this, "Couldn't start voice: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            VoxynLogger.w("Overlay permission not granted — skipping OverlayService", "MAIN")
            return
        }
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
