package com.voxyn.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import kotlin.math.abs

enum class IslandState {
    IDLE, LISTENING, PROCESSING, SPEAKING, CONFIRMATION, SUCCESS, ERROR
}

class DynamicIsland(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private var currentState: IslandState = IslandState.IDLE
    private var statusText: String = "VOXYN"

    // Dimensions (in px)
    private val heightPx      = (36 * density).toInt()
    private val cornerRadiusPx = 18f * density
    private var currentWidthPx = (120 * density)

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2000000") // black 95%
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#3300E5FF")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        isFakeBoldText = true
        color = Color.parseColor("#00E5FF")
        textAlign = Paint.Align.LEFT
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFAB00")
    }

    // Animation state
    private var spinnerAngle   = 0f
    private var pulseOffset    = 0f
    private var pulseGrowing   = true
    private var widthAnimator  : ValueAnimator? = null
    private val handler        = Handler(Looper.getMainLooper())

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (currentState != IslandState.LISTENING) return
            pulseOffset = if (pulseGrowing) (pulseOffset + 0.3f).coerceAtMost(3f)
                          else (pulseOffset - 0.3f).coerceAtLeast(0f)
            if (pulseOffset >= 3f) pulseGrowing = false
            if (pulseOffset <= 0f) pulseGrowing = true
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    private val spinnerRunnable = object : Runnable {
        override fun run() {
            if (currentState != IslandState.PROCESSING) return
            spinnerAngle = (spinnerAngle + 14f) % 360f
            invalidate()
            handler.postDelayed(this, 28)
        }
    }

    // Touch
    private var touchDownX   = 0f
    private var touchDownY   = 0f
    private var touchDownMs  = 0L

    private val longPressRunnable = Runnable {
        VoxynLogger.i("DynamicIsland long-pressed — pausing listening", "OVERLAY")
        try {
            context.startService(Intent(context, VoiceInputService::class.java).apply {
                action = VoiceInputService.ACTION_STOP_LISTENING
            })
        } catch (_: Exception) {}
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ─── Public API ───────────────────────────────────────────────────

    fun updateState(state: IslandState, text: String? = null) {
        // Must be called on main thread
        handler.post {
            currentState = state
            handler.removeCallbacks(pulseRunnable)
            handler.removeCallbacks(spinnerRunnable)
            handler.removeCallbacks(longPressRunnable)

            when (state) {
                IslandState.IDLE -> {
                    statusText = "VOXYN"
                    textPaint.color = Color.parseColor("#00E5FF")
                    dotPaint.color  = Color.parseColor("#00E676")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#3300E5FF")
                    animateWidth(120 * density)
                }
                IslandState.LISTENING -> {
                    statusText = text ?: "Listening..."
                    textPaint.color = Color.WHITE
                    dotPaint.color  = Color.parseColor("#00E5FF")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#5500E5FF")
                    animateWidth(200 * density)
                    handler.post(pulseRunnable)
                }
                IslandState.PROCESSING -> {
                    statusText = text ?: "Thinking..."
                    textPaint.color = Color.parseColor("#FFAB00")
                    spinnerPaint.color = Color.parseColor("#FFAB00")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#33FFAB00")
                    animateWidth(180 * density)
                    handler.post(spinnerRunnable)
                }
                IslandState.SPEAKING -> {
                    val display = text ?: "Processing..."
                    statusText = display
                    textPaint.color = Color.parseColor("#00E5FF")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#3300E5FF")
                    val w = textPaint.measureText(statusText) + 48 * density
                    animateWidth(w.coerceIn(160 * density, screenWidth() * 0.82f))
                }
                IslandState.CONFIRMATION -> {
                    statusText = text ?: "Confirm action?"
                    textPaint.color = Color.WHITE
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#33FFAB00")
                    val w = textPaint.measureText(statusText) + 48 * density
                    animateWidth(w.coerceIn(220 * density, screenWidth() * 0.82f))
                }
                IslandState.SUCCESS -> {
                    statusText = text ?: "Done!"
                    textPaint.color = Color.WHITE
                    bgPaint.color   = Color.parseColor("#F200C853") // green bg
                    borderPaint.color = Color.parseColor("#5500E676")
                    val w = textPaint.measureText(statusText) + 48 * density
                    animateWidth(w.coerceIn(140 * density, screenWidth() * 0.82f))
                    handler.postDelayed({
                        bgPaint.color = Color.parseColor("#F2000000")
                        updateState(IslandState.IDLE)
                    }, 1500L)
                }
                IslandState.ERROR -> {
                    statusText = text ?: "Error"
                    textPaint.color = Color.WHITE
                    bgPaint.color   = Color.parseColor("#F2C62828") // red bg
                    borderPaint.color = Color.parseColor("#55FF1744")
                    val w = textPaint.measureText(statusText) + 48 * density
                    animateWidth(w.coerceIn(140 * density, screenWidth() * 0.82f))
                    handler.postDelayed({
                        bgPaint.color = Color.parseColor("#F2000000")
                        updateState(IslandState.IDLE)
                    }, 1500L)
                }
            }
            invalidate()
        }
    }

    // ─── Width animation — updates WindowManager LayoutParams ──────────

    private fun animateWidth(targetPx: Float) {
        if (kotlin.math.abs(targetPx - currentWidthPx) < 2f) return
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofFloat(currentWidthPx, targetPx).apply {
            duration = 280L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                currentWidthPx = anim.animatedValue as Float
                lp.width = currentWidthPx.toInt()
                try {
                    wm.updateViewLayout(this@DynamicIsland, lp)
                } catch (_: Exception) {}
                invalidate()
            }
            start()
        }
    }

    private fun screenWidth(): Float =
        context.resources.displayMetrics.widthPixels.toFloat()

    // ─── Drawing ──────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Background pill
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, bgPaint)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, borderPaint)

        val cy = h / 2f
        val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f

        when (currentState) {
            IslandState.IDLE -> {
                // Green dot + "VOXYN"
                val dotR  = 4f * density
                val dotCx = cornerRadiusPx
                canvas.drawCircle(dotCx, cy, dotR, dotPaint)
                canvas.drawText(statusText, dotCx + dotR + 6 * density, textY, textPaint)
            }

            IslandState.LISTENING -> {
                // Pulsing dot + text
                val dotR  = 4f * density + pulseOffset
                val dotCx = cornerRadiusPx
                canvas.drawCircle(dotCx, cy, dotR, dotPaint)
                canvas.drawText(statusText, dotCx + 4f * density + dotR + 4 * density, textY, textPaint)
            }

            IslandState.PROCESSING -> {
                // Spinning arc + "Thinking..."
                val spinR  = 9f * density
                val spinCx = cornerRadiusPx + spinR
                val spinRect = RectF(spinCx - spinR, cy - spinR, spinCx + spinR, cy + spinR)
                canvas.drawArc(spinRect, spinnerAngle, 250f, false, spinnerPaint)
                canvas.drawText(statusText, spinCx + spinR + 6 * density, textY, textPaint)
            }

            IslandState.SPEAKING,
            IslandState.CONFIRMATION -> {
                // Centered text with padding
                val tx = cornerRadiusPx
                canvas.drawText(statusText, tx, textY, textPaint)
            }

            IslandState.SUCCESS,
            IslandState.ERROR -> {
                // Center-aligned text
                val textW = textPaint.measureText(statusText)
                val tx = (w - textW) / 2f
                canvas.drawText(statusText, tx, textY, textPaint)
            }
        }
    }

    // ─── Touch ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX  = event.rawX
                touchDownY  = event.rawY
                touchDownMs = System.currentTimeMillis()
                handler.postDelayed(longPressRunnable, 600L)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val dt = System.currentTimeMillis() - touchDownMs
                val dx = abs(event.rawX - touchDownX)
                val dy = abs(event.rawY - touchDownY)
                if (dt < 600L && dx < 20f && dy < 20f) {
                    // Tap → open VOXYN
                    try {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                    } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
