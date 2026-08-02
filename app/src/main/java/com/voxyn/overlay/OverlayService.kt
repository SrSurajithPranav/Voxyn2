package com.voxyn.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.voxyn.utils.VoxynLogger
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "voxyn_overlay"
        const val NOTIF_ID   = 3003

        // State colors
        val COLOR_IDLE      = Color.parseColor("#33006B7A")  // dim cyan
        val COLOR_LISTENING = Color.parseColor("#FF00E5FF")  // bright cyan
        val COLOR_THINKING  = Color.parseColor("#FFFFAB00")  // amber
        val COLOR_EXECUTING = Color.parseColor("#FF00E676")  // green
        val COLOR_SUCCESS   = Color.parseColor("#FF00E676")  // green
        val COLOR_ERROR     = Color.parseColor("#FFFF1744")  // red
    }

    private var wm: WindowManager? = null
    private var orbView: OrbView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        if (android.provider.Settings.canDrawOverlays(this)) {
            showOrb()
        } else {
            VoxynLogger.w("Overlay permission not granted", "OVERLAY")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("state")?.let { setState(it) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOrb()
        super.onDestroy()
    }

    // ─── Orb ──────────────────────────────────────────────────────────

    private fun showOrb() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getMetrics(metrics)
        val screenWidth = metrics.widthPixels

        // Position: 15% from left edge — avoids camera punch-hole
        val xOffset = (screenWidth * 0.15f).toInt()

        val orbSize = (metrics.density * 52).toInt()

        params = WindowManager.LayoutParams(
            orbSize, orbSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xOffset
            y = 24
        }

        orbView = OrbView(this).apply {
            // Touch: raw Android View setOnTouchListener — no Compose machinery
            var downX = 0f
            var downY = 0f
            var initialParamX = 0
            var initialParamY = 0

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initialParamX = params!!.x
                        initialParamY = params!!.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        params!!.x = initialParamX + dx
                        params!!.y = initialParamY + dy
                        wm?.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // If barely moved = tap, not drag
                        val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                        if (moved < 12f) {
                            val i = Intent(this@OverlayService, Class.forName("com.voxyn.MainActivity")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(i)
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            wm?.addView(orbView, params)
            startIdlePulse()
            VoxynLogger.i("Orb shown", "OVERLAY")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to show orb: ${e.message}", tag = "OVERLAY")
        }
    }

    private fun removeOrb() {
        pulseRunnable?.let { handler.removeCallbacks(it) }
        try { orbView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        orbView = null
    }

    fun setState(state: String) {
        val color = when (state) {
            "LISTENING"  -> COLOR_LISTENING
            "THINKING"   -> COLOR_THINKING
            "EXECUTING"  -> COLOR_EXECUTING
            "SUCCESS"    -> COLOR_SUCCESS
            "ERROR"      -> COLOR_ERROR
            else         -> COLOR_IDLE
        }
        handler.post {
            orbView?.setColor(color)
            if (state == "IDLE") startIdlePulse() else stopIdlePulse()
            if (state in listOf("SUCCESS", "ERROR")) {
                handler.postDelayed({ setState("IDLE") }, 2000)
            }
        }
    }

    private var pulseUp = true

    private fun startIdlePulse() {
        pulseRunnable?.let { handler.removeCallbacks(it) }
        pulseRunnable = object : Runnable {
            override fun run() {
                val current = orbView?.getOrbAlpha() ?: 0.3f
                val target = if (pulseUp) 0.9f else 0.25f
                val newAlpha = if (pulseUp) minOf(current + 0.05f, 0.9f)
                              else maxOf(current - 0.05f, 0.25f)
                orbView?.setOrbAlpha(newAlpha)
                if ((pulseUp && newAlpha >= 0.9f) || (!pulseUp && newAlpha <= 0.25f)) {
                    pulseUp = !pulseUp
                }
                handler.postDelayed(this, 60)
            }
        }
        handler.post(pulseRunnable!!)
    }

    private fun stopIdlePulse() {
        pulseRunnable?.let { handler.removeCallbacks(it) }
        orbView?.setOrbAlpha(1f)
    }

    // ─── Notification ─────────────────────────────────────────────────

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN").setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VOXYN Overlay", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
        }
    }
}

// ─── Orb View ─────────────────────────────────────────────────────────────────

class OrbView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayService.COLOR_IDLE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4400E5FF")
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun getOrbAlpha(): Float = paint.alpha / 255f

    fun setOrbAlpha(alpha: Float) {
        paint.alpha = (alpha * 255).toInt()
        borderPaint.alpha = (alpha * 80).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 4f
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
    }
}
