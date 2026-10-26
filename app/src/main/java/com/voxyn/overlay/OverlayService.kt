package com.voxyn.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.voxyn.MainActivity
import com.voxyn.engine.VoxynEngine
import com.voxyn.utils.VoxynLogger
import org.koin.android.ext.android.inject

class OverlayService : Service() {

    private val surfaceStore: IslandSurfaceStore by inject()
    private val engine: VoxynEngine by inject()
    private var batteryReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    companion object {
        const val CHANNEL_ID = "voxyn_overlay"
        const val NOTIF_ID   = 3003

        // Singleton so VoxynEngine can call setState directly without startService overhead
        var instance: OverlayService? = null
            private set
    }

    private var wm: WindowManager? = null
    private var dynamicIsland: DynamicIsland? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // Queue state changes that arrive before the island is ready
    private var pendingState: Pair<String, String?>? = null
    private var islandReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        VoxynLogger.i("OverlayService created", "OVERLAY")
        registerDeviceEvents()

        // Post island creation to main thread to ensure it runs after startForeground
        handler.post {
            if (android.provider.Settings.canDrawOverlays(this)) {
                showDynamicIsland()
            } else {
                VoxynLogger.w("Overlay permission not granted — DynamicIsland won't show", "OVERLAY")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stateStr = intent?.getStringExtra("state") ?: return START_STICKY
        val textStr  = intent.getStringExtra("text")

        // If island isn't ready yet, save the state and apply it once ready
        if (!islandReady) {
            pendingState = Pair(stateStr, textStr)
            VoxynLogger.d("Island not ready yet, queuing state: $stateStr", "OVERLAY")
        } else {
            handler.post { applyState(stateStr, textStr) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        unregisterDeviceEvents()
        removeDynamicIsland()
        super.onDestroy()
    }

    // ─── Island creation ──────────────────────────────────────────────

    private fun showDynamicIsland() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            VoxynLogger.w("Cannot draw overlays — skipping DynamicIsland creation", "OVERLAY")
            return
        }
        try {
            removeDynamicIsland()
            wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val windowManager = wm ?: return
            val density = resources.displayMetrics.density
            val topMarginPx = (8 * density).toInt()

            // Fixed size layout — DynamicIsland manages its own width via animation
            val initialWidthPx = (120 * density).toInt()
            val heightPx = (36 * density).toInt()

            layoutParams = WindowManager.LayoutParams(
                initialWidthPx,
                heightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topMarginPx
            }

            val island = DynamicIsland(this, windowManager, layoutParams!!, surfaceStore) { command ->
                if (!engine.isProcessing()) engine.process(command)
                else VoxynLogger.w("Island shortcut ignored while the voice pipeline is busy", "OVERLAY")
            }
            dynamicIsland = island

            windowManager.addView(island, layoutParams)
            islandReady = true
            VoxynLogger.i("DynamicIsland overlay added (120dp × 36dp @ y=8dp, top-center)", "OVERLAY")

            // Apply any state that arrived before we were ready
            pendingState?.let { (s, t) ->
                pendingState = null
                handler.postDelayed({ applyState(s, t) }, 100L)
            }

        } catch (e: Exception) {
            VoxynLogger.e("Failed to add DynamicIsland: ${e.message}", tag = "OVERLAY")
            dynamicIsland = null
            islandReady = false
        }
    }

    private fun removeDynamicIsland() {
        try {
            val island = dynamicIsland
            if (island != null) {
                wm?.removeView(island)
            }
        } catch (_: Exception) {}
        dynamicIsland = null
        islandReady = false
    }

    // ─── State ────────────────────────────────────────────────────────

    fun setState(stateStr: String, textStr: String? = null) {
        val state = when (stateStr.uppercase()) {
            "STOPPED" -> IslandState.STOPPED
            "LISTENING" -> IslandState.LISTENING
            "PROCESSING", "THINKING" -> IslandState.PROCESSING
            "EXECUTING" -> IslandState.EXECUTING
            "SPEAKING" -> IslandState.SPEAKING
            "CONFIRMATION" -> IslandState.CONFIRMATION
            "SUCCESS" -> IslandState.SUCCESS
            "ERROR" -> IslandState.ERROR
            "RECOVERING" -> IslandState.RECOVERING
            else -> IslandState.IDLE
        }
        surfaceStore.setVoiceState(state, textStr)
        if (!islandReady) {
            pendingState = Pair(stateStr, textStr)
            return
        }
        handler.post { applyState(stateStr, textStr) }
    }

    private fun registerDeviceEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
                val plugged = battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                surfaceStore.setBattery(percent, plugged != 0)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batteryReceiver, filter)
            }
            batteryReceiver?.onReceive(this, null)
        } catch (e: Exception) {
            VoxynLogger.w("Battery status unavailable: ${e.message}", "OVERLAY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val callback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateHeadphones(audioManager)
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateHeadphones(audioManager)
                }
                audioDeviceCallback = callback
                audioManager.registerAudioDeviceCallback(callback, handler)
                updateHeadphones(audioManager)
            } catch (e: Exception) {
                VoxynLogger.w("Headphone status unavailable: ${e.message}", "OVERLAY")
                surfaceStore.setHeadphonesConnected(false, available = false)
            }
        }
    }

    private fun updateHeadphones(audioManager: AudioManager) {
        try {
            val headphoneTypes = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET
            )
            surfaceStore.setHeadphonesConnected(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes })
        } catch (e: SecurityException) {
            surfaceStore.setHeadphonesConnected(false, available = false)
            VoxynLogger.w("Headphone status requires device access: ${e.message}", "OVERLAY")
        }
    }

    private fun unregisterDeviceEvents() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let {
                try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it) } catch (_: Exception) {}
            }
        }
        audioDeviceCallback = null
    }

    fun setMicrophoneLevel(rmsDb: Float) {
        handler.post { dynamicIsland?.updateAudioLevel(rmsDb) }
    }

    fun setSpeechActive(active: Boolean) {
        handler.post { dynamicIsland?.setSpeechActive(active) }
    }

    private fun applyState(stateStr: String, textStr: String?) {
        val island = dynamicIsland ?: return
        val state = when (stateStr.uppercase()) {
            "STOPPED"     -> IslandState.STOPPED
            "LISTENING"    -> IslandState.LISTENING
            "PROCESSING",
            "THINKING",
            "EXECUTING"    -> IslandState.EXECUTING
            "SPEAKING"    -> IslandState.SPEAKING
            "CONFIRMATION" -> IslandState.CONFIRMATION
            "SUCCESS"      -> IslandState.SUCCESS
            "ERROR"        -> IslandState.ERROR
            "RECOVERING"   -> IslandState.RECOVERING
            else           -> IslandState.IDLE
        }
        island.updateState(state, textStr)
    }

    // ─── Notification ─────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN Dynamic Island")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VOXYN Dynamic Island", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
        }
    }
}
