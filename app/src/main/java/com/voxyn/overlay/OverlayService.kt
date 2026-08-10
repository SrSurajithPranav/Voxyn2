package com.voxyn.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger

class OverlayService : Service() {

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
        startForeground(NOTIF_ID, buildNotification())
        VoxynLogger.i("OverlayService created", "OVERLAY")

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
        removeDynamicIsland()
        super.onDestroy()
    }

    // ─── Island creation ──────────────────────────────────────────────

    private fun showDynamicIsland() {
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topMarginPx
            }

            dynamicIsland = DynamicIsland(this, wm!!, layoutParams!!)

            wm?.addView(dynamicIsland, layoutParams)
            islandReady = true
            VoxynLogger.i("DynamicIsland overlay added (120dp × 36dp @ y=8dp, top-center)", "OVERLAY")

            // Apply any state that arrived before we were ready
            pendingState?.let { (s, t) ->
                pendingState = null
                handler.postDelayed({ applyState(s, t) }, 100L)
            }

        } catch (e: Exception) {
            VoxynLogger.e("Failed to add DynamicIsland: ${e.message}", tag = "OVERLAY")
        }
    }

    private fun removeDynamicIsland() {
        try {
            dynamicIsland?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        dynamicIsland = null
        islandReady = false
    }

    // ─── State ────────────────────────────────────────────────────────

    fun setState(stateStr: String, textStr: String? = null) {
        if (!islandReady) {
            pendingState = Pair(stateStr, textStr)
            return
        }
        handler.post { applyState(stateStr, textStr) }
    }

    private fun applyState(stateStr: String, textStr: String?) {
        val island = dynamicIsland ?: return
        val state = when (stateStr.uppercase()) {
            "LISTENING"    -> IslandState.LISTENING
            "PROCESSING",
            "THINKING"     -> IslandState.PROCESSING
            "SPEAKING",
            "EXECUTING"    -> IslandState.SPEAKING
            "CONFIRMATION" -> IslandState.CONFIRMATION
            "SUCCESS"      -> IslandState.SUCCESS
            "ERROR"        -> IslandState.ERROR
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
