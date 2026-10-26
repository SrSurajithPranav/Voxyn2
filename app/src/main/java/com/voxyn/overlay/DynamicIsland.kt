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
    STOPPED, IDLE, LISTENING, PROCESSING, SPEAKING, RECOVERING, CONFIRMATION, SUCCESS, ERROR
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
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val voiceGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(8f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    // Animation state
    private var spinnerAngle   = 0f
    private var audioLevel = 0f
    private var lastAudioLevelAtMs = 0L
    private var widthAnimator  : ValueAnimator? = null
    private val handler        = Handler(Looper.getMainLooper())
    private val animationsAllowed: Boolean
        get() = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f

    private val spinnerRunnable = object : Runnable {
        override fun run() {
            if (currentState != IslandState.PROCESSING) return
            if (!isShown || !animationsAllowed) return
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

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(spinnerRunnable)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (currentState == IslandState.PROCESSING && animationsAllowed) handler.post(spinnerRunnable)
    }

    // ─── Public API ───────────────────────────────────────────────────

    fun updateState(state: IslandState, text: String? = null) {
        // Must be called on main thread
        handler.post {
            currentState = state
            handler.removeCallbacks(spinnerRunnable)
            handler.removeCallbacks(longPressRunnable)

            when (state) {
                IslandState.STOPPED, IslandState.IDLE -> {
                    audioLevel = 0f
                    statusText = "VOXYN"
                    textPaint.color = Color.parseColor("#00E5FF")
                    dotPaint.color  = Color.parseColor("#00E676")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#3300E5FF")
                    animateWidth(120 * density)
                }
                IslandState.LISTENING -> {
                    audioLevel = 0f
                    statusText = text ?: "Listening..."
                    textPaint.color = Color.WHITE
                    dotPaint.color  = Color.parseColor("#00E5FF")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#5500E5FF")
                    animateWidth(200 * density)
                }
                IslandState.PROCESSING -> {
                    statusText = text ?: "Thinking..."
                    textPaint.color = Color.parseColor("#FFAB00")
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
                    val w = textPaint.measureText(statusText) + 64 * density
                    animateWidth(w.coerceIn(160 * density, screenWidth() * 0.82f))
                }
                IslandState.RECOVERING -> {
                    statusText = text ?: "Reconnecting..."
                    textPaint.color = Color.parseColor("#FFAB00")
                    dotPaint.color = Color.parseColor("#FFAB00")
                    bgPaint.color = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#33FFAB00")
                    animateWidth(190 * density)
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

    /** Called only with levels reported by Android's active SpeechRecognizer. */
    fun updateAudioLevel(rmsDb: Float) {
        if (currentState != IslandState.LISTENING) return
        val target = ((rmsDb + 55f) / 40f).coerceIn(0f, 1f)
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = if (lastAudioLevelAtMs == 0L) 32L else (now - lastAudioLevelAtMs).coerceIn(1L, 250L)
        lastAudioLevelAtMs = now
        val timeConstantMs = if (target > audioLevel) 60f else 280f
        val smoothing = 1f - kotlin.math.exp(-elapsed / timeConstantMs)
        audioLevel += (target - audioLevel) * smoothing
        invalidate()
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
            IslandState.STOPPED, IslandState.IDLE -> {
                // Green dot + "VOXYN"
                val dotR  = 4f * density
                val dotCx = cornerRadiusPx
                canvas.drawCircle(dotCx, cy, dotR, dotPaint)
                canvas.drawText(statusText, dotCx + dotR + 6 * density, textY, textPaint)
            }

            IslandState.LISTENING -> {
                // Pulsing dot + text
                val dotR  = 4f * density + audioLevel * 4f * density
                val dotCx = cornerRadiusPx
                if (audioLevel > 0.04f) {
                    voiceGlowPaint.color = Color.argb((audioLevel * 72).toInt(), 0, 229, 255)
                    canvas.drawCircle(dotCx, cy, dotR + 2f * density, voiceGlowPaint)
                }
                canvas.drawCircle(dotCx, cy, dotR, dotPaint)
                canvas.drawText(statusText, dotCx + 4f * density + dotR + 4 * density, textY, textPaint)
            }

            IslandState.PROCESSING -> {
                // Compact native Thinking Orb: one core and three orbiting particles.
                val centerX = 19f * density
                val orbitR = 6f * density
                val baseAngle = Math.toRadians(spinnerAngle.toDouble())
                val particleColors = intArrayOf(
                    Color.parseColor("#FFAB00"),
                    Color.parseColor("#FFE082"),
                    Color.parseColor("#FFF3CD")
                )
                for (i in 0..2) {
                    val angle = baseAngle + i * (Math.PI * 2.0 / 3.0)
                    val x = centerX + kotlin.math.cos(angle).toFloat() * orbitR
                    val y = cy + kotlin.math.sin(angle).toFloat() * orbitR
                    orbPaint.color = particleColors[i]
                    canvas.drawCircle(x, y, (1.4f + if (i == 0) 0.35f else 0f) * density, orbPaint)
                }
                orbPaint.color = Color.parseColor("#FFFFD180")
                canvas.drawCircle(centerX, cy, 2.1f * density, orbPaint)
                canvas.drawText(statusText, 34f * density, textY, textPaint)
            }

            IslandState.SPEAKING -> {
                drawBotAvatar(canvas, 21f * density, cy)
                canvas.drawText(statusText, 40f * density, textY, textPaint)
            }

            IslandState.RECOVERING,
            IslandState.CONFIRMATION -> {
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

    /** A small native bot mark used only while the pipeline reports SPEAKING. */
    private fun drawBotAvatar(canvas: Canvas, cx: Float, cy: Float) {
        avatarPaint.color = Color.parseColor("#00B8D4")
        canvas.drawRoundRect(
            RectF(cx - 6f * density, cy - 6f * density, cx + 6f * density, cy + 5f * density),
            4f * density, 4f * density, avatarPaint
        )
        avatarPaint.color = Color.parseColor("#B2EBF2")
        canvas.drawCircle(cx - 2.2f * density, cy - 0.4f * density, 1f * density, avatarPaint)
        canvas.drawCircle(cx + 2.2f * density, cy - 0.4f * density, 1f * density, avatarPaint)
        avatarPaint.color = Color.parseColor("#00B8D4")
        canvas.drawRect(cx - 0.45f * density, cy - 8f * density, cx + 0.45f * density, cy - 5.5f * density, avatarPaint)
        canvas.drawCircle(cx, cy - 8f * density, 1f * density, avatarPaint)
        canvas.drawRoundRect(
            RectF(cx - 4f * density, cy + 5.5f * density, cx + 4f * density, cy + 8f * density),
            1.2f * density, 1.2f * density, avatarPaint
        )
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
