package com.voxyn

import android.Manifest
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
import com.voxyn.receiver.BootReceiver
import com.voxyn.receiver.VoiceStartupPolicy
import com.voxyn.wake.WakeWordService
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
        } else {
            startVoxynServices()
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
                        onRequestAllFilesAccess = ::requestAllFilesAccess,
                        onPauseVoiceServices = ::pauseVoiceServices,
                        onResumeVoiceServices = ::startVoxynServices
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
            startVoxynServices()
            checkOverlayPermission()
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
            VoxynLogger.w("LISTENING_NOT_ELIGIBLE reason=MICROPHONE_PERMISSION_REQUIRED", "MAIN")
            return
        }
        val bootDeferred = getSharedPreferences(BootReceiver.STARTUP_PREFS, MODE_PRIVATE)
            .getBoolean(BootReceiver.KEY_BOOT_REQUIRES_VISIBLE_START, false)
        if (bootDeferred) {
            getSharedPreferences(BootReceiver.STARTUP_PREFS, MODE_PRIVATE).edit()
                .remove(BootReceiver.KEY_BOOT_REQUIRES_VISIBLE_START)
                .apply()
            VoxynLogger.i("VISIBLE_USER_ENTRY_AFTER_BOOT; attempting microphone service start", "MAIN")
        } else {
            VoxynLogger.i("LISTENING_ELIGIBLE source=VISIBLE_ACTIVITY", "MAIN")
        }
        try {
            startForegroundService(Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
            })
            VoxynLogger.i("MICROPHONE_SERVICE_START_REQUESTED source=VISIBLE_ACTIVITY", "MAIN")
        } catch (e: Exception) {
            val category = VoiceStartupPolicy.startupFailureCategory(e.javaClass.simpleName)
            VoxynLogger.e(
                "$category source=VISIBLE_ACTIVITY exception=${e.javaClass.simpleName}; listening is not active",
                tag = "MAIN"
            )
        }
    }

    private fun pauseVoiceServices() {
        stopService(Intent(this, com.voxyn.voice.VoiceInputService::class.java))
        stopService(Intent(this, WakeWordService::class.java))
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
