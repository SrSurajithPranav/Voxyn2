package com.voxyn.perception

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.voxyn.utils.VoxynLogger

data class DeviceContextSnapshot(
    val foregroundApp: String?,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isWifiConnected: Boolean,
    val isMusicActive: Boolean,
    val isHeadsetConnected: Boolean,
    val isScreenOn: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ContextEngine(private val context: Context) {

    var currentApp: String? = null
        private set

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    fun updateCurrentApp(packageName: String) {
        currentApp = packageName
        VoxynLogger.d("Foreground app updated: $packageName", "CONTEXT")
    }

    fun getSnapshot(): DeviceContextSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val isMusic = audioManager.isMusicActive
        @Suppress("DEPRECATION")
        val isHeadset = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn

        val isScreenOn = powerManager.isInteractive

        return DeviceContextSnapshot(
            foregroundApp = currentApp,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isWifiConnected = isWifi,
            isMusicActive = isMusic,
            isHeadsetConnected = isHeadset,
            isScreenOn = isScreenOn
        )
    }

    fun getContextSummary(): String {
        val snapshot = getSnapshot()
        return buildString {
            snapshot.foregroundApp?.let { append("Foreground app: $it; ") }
            if (snapshot.batteryPercent >= 0) {
                append("Battery: ${snapshot.batteryPercent}%${if (snapshot.isCharging) " (charging)" else ""}; ")
            }
            if (snapshot.isWifiConnected) append("Wi-Fi: connected; ")
            if (snapshot.isMusicActive) append("Media: playing; ")
            if (snapshot.isHeadsetConnected) append("Audio: headset connected; ")
        }.trim().removeSuffix(";").ifEmpty { "No active context" }
    }
}
