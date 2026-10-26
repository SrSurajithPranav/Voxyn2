package com.voxyn.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.view.animation.AccelerateDecelerateInterpolator
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class IslandState {
    STOPPED, IDLE, LISTENING, PROCESSING, EXECUTING, SPEAKING, RECOVERING, CONFIRMATION, SUCCESS, ERROR
}

class DynamicIsland(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val surfaceStore: IslandSurfaceStore,
    private val onShortcut: (String) -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private var currentState: IslandState = IslandState.IDLE
    private var statusText: String = "VOXYN"

    // Dimensions (in px)
    private val heightPx      = (36 * density).toInt()
    private val cornerRadiusPx = 18f * density
    private var currentWidthPx = (120 * density)
    private val compactHeightPx = (36 * density).toInt()
    private val compactWidthPx = (120 * density).toInt()
    private var expanded = false
    private var expandedWidthAnimator: ValueAnimator? = null
    private var expandedHeightAnimator: ValueAnimator? = null
    private val panel: ComposeView
    private var stateCollection: CoroutineScope? = null
    private var stateCollectionJob: Job? = null

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
    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            15f * density, 12f * density, 27f * density, 24f * density,
            intArrayOf(Color.parseColor("#80DEEA"), Color.parseColor("#00ACC1"), Color.parseColor("#006978")),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val avatarRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = Color.parseColor("#B2EBF2")
    }
    private val avatarFeaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val voiceGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(8f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val voiceGlowRect = RectF()
    private val avatarHeadRect = RectF()
    private val avatarMouthRect = RectF()
    private val avatarBodyRect = RectF()

    // Animation state
    private var spinnerAngle   = 0f
    private var audioLevel = 0f
    private var lastAudioLevelAtMs = 0L
    private var speechActive = false
    private var avatarPhase = 0f
    private var restingEyesClosed = false
    private var widthAnimator  : ValueAnimator? = null
    private var transientStateRunnable: Runnable? = null
    private val handler        = Handler(Looper.getMainLooper())
    private val animationsAllowed: Boolean
        get() = ValueAnimator.areAnimatorsEnabled()

    private val animationRunnable = object : Runnable {
        override fun run() {
            val animateOrb = currentState == IslandState.PROCESSING
            val animateAvatar = currentState == IslandState.SPEAKING && speechActive
            if (!animateOrb && !animateAvatar) return
            if (!isShown || !animationsAllowed) return
            if (animateOrb) spinnerAngle = (spinnerAngle + 6f) % 360f
            if (animateAvatar) avatarPhase = (avatarPhase + 0.32f) % (Math.PI.toFloat() * 2f)
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }
    private val blinkOpenRunnable = Runnable {
        restingEyesClosed = false
        invalidate()
        scheduleIdleBlink()
    }
    private val idleBlinkRunnable = Runnable {
        if (canShowRestingMascot()) {
            restingEyesClosed = true
            invalidate()
            handler.postDelayed(blinkOpenRunnable, 120L)
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
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        panel = ComposeView(context).apply {
            visibility = GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IslandExpandedPanel(surfaceStore, ::collapse, onShortcut) }
        }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(animationRunnable)
        handler.removeCallbacks(idleBlinkRunnable)
        handler.removeCallbacks(blinkOpenRunnable)
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        stateCollection?.cancel()
        stateCollection = null
        expandedWidthAnimator?.cancel()
        expandedHeightAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stateCollection = collectionScope
        stateCollectionJob = collectionScope.launch {
            surfaceStore.state.collect {
                invalidate()
                scheduleIdleBlink()
            }
        }
        scheduleAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        scheduleAnimation()
        scheduleIdleBlink()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (expanded && event.action == MotionEvent.ACTION_OUTSIDE) {
            collapse()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // ─── Public API ───────────────────────────────────────────────────

    fun updateState(state: IslandState, text: String? = null) {
        // Must be called on main thread
        handler.post {
            transientStateRunnable?.let(handler::removeCallbacks)
            transientStateRunnable = null
            currentState = state
            restingEyesClosed = false
            surfaceStore.setVoiceState(state, text)
            handler.removeCallbacks(animationRunnable)
            handler.removeCallbacks(longPressRunnable)

            when (state) {
                IslandState.STOPPED, IslandState.IDLE -> {
                    audioLevel = 0f
                    lastAudioLevelAtMs = 0L
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
                IslandState.PROCESSING, IslandState.EXECUTING -> {
                    statusText = text ?: "Thinking..."
                    textPaint.color = Color.parseColor("#FFAB00")
                    bgPaint.color   = Color.parseColor("#F2000000")
                    borderPaint.color = Color.parseColor("#33FFAB00")
                    animateWidth(180 * density)
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
                    transientStateRunnable = Runnable {
                        bgPaint.color = Color.parseColor("#F2000000")
                        updateState(IslandState.IDLE)
                    }
                    handler.postDelayed(transientStateRunnable!!, 1500L)
                }
                IslandState.ERROR -> {
                    statusText = text ?: "Error"
                    textPaint.color = Color.WHITE
                    bgPaint.color   = Color.parseColor("#F2C62828") // red bg
                    borderPaint.color = Color.parseColor("#55FF1744")
                    val w = textPaint.measureText(statusText) + 48 * density
                    animateWidth(w.coerceIn(140 * density, screenWidth() * 0.82f))
                    transientStateRunnable = Runnable {
                        bgPaint.color = Color.parseColor("#F2000000")
                        updateState(IslandState.IDLE)
                    }
                    handler.postDelayed(transientStateRunnable!!, 1500L)
                }
            }
            scheduleAnimation()
            invalidate()
        }
    }

    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        if (expanded) return
        widthAnimator?.cancel()
        expanded = true
        panel.visibility = VISIBLE
        panel.bringToFront()
        surfaceStore.setExpanded(true)
        val targetWidth = (screenWidth() * 0.92f).coerceAtMost(420f * density).toInt()
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else (24f * density).toInt()
        val targetHeight = (460f * density).toInt().coerceAtMost(
            (resources.displayMetrics.heightPixels - statusBarHeight - 32f * density).toInt().coerceAtLeast((300f * density).toInt())
        )
        lp.y = (8f * density).toInt() + statusBarHeight
        animateWindowSize(targetWidth, targetHeight)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        surfaceStore.setExpanded(false)
        panel.visibility = GONE
        lp.y = (8f * density).toInt()
        animateWindowSize(currentWidthPx.toInt().coerceAtMost(screenWidth().toInt()), compactHeightPx)
    }

    private fun animateWindowSize(targetWidth: Int, targetHeight: Int) {
        expandedWidthAnimator?.cancel()
        expandedHeightAnimator?.cancel()
        val fromW = width.takeIf { it > 0 } ?: lp.width
        val fromH = height.takeIf { it > 0 } ?: lp.height
        expandedWidthAnimator = ValueAnimator.ofInt(fromW, targetWidth).apply {
            duration = 240L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { lp.width = it.animatedValue as Int; updateWindowLayout() }
            start()
        }
        expandedHeightAnimator = ValueAnimator.ofInt(fromH, targetHeight).apply {
            duration = 240L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { lp.height = it.animatedValue as Int; updateWindowLayout() }
            start()
        }
    }

    private fun updateWindowLayout() {
        try { wm.updateViewLayout(this, lp) } catch (e: Exception) { VoxynLogger.w("Island resize failed: ${e.message}", "OVERLAY") }
    }

    /** Called with RMS levels from the PCM buffers of the active recognizer. */
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

    fun setSpeechActive(active: Boolean) {
        handler.post {
            speechActive = active
            if (!active) avatarPhase = 0f
            scheduleAnimation()
            invalidate()
        }
    }

    private fun scheduleAnimation() {
        handler.removeCallbacks(animationRunnable)
        val shouldAnimate = currentState == IslandState.PROCESSING || currentState == IslandState.EXECUTING ||
            (currentState == IslandState.SPEAKING && speechActive)
        if (isShown && animationsAllowed && shouldAnimate) handler.post(animationRunnable)
        scheduleIdleBlink()
    }

    private fun canShowRestingMascot(): Boolean = isShown && animationsAllowed &&
        currentState == IslandState.IDLE && surfaceStore.state.value.mascotEnabled &&
        !surfaceStore.state.value.minimalNotch && !expanded

    private fun scheduleIdleBlink() {
        handler.removeCallbacks(idleBlinkRunnable)
        if (canShowRestingMascot()) handler.postDelayed(idleBlinkRunnable, 4_800L)
        if (!canShowRestingMascot()) {
            handler.removeCallbacks(blinkOpenRunnable)
            if (restingEyesClosed) {
                restingEyesClosed = false
                invalidate()
            }
        }
    }

    // ─── Width animation — updates WindowManager LayoutParams ──────────

    private fun animateWidth(targetPx: Float) {
        if (expanded) return
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
                if (surfaceStore.state.value.minimalNotch) {
                    canvas.drawRoundRect(w * .38f, cy - density, w * .62f, cy + density, density, density, dotPaint)
                    return
                }
                if (surfaceStore.state.value.mascotEnabled && currentState == IslandState.IDLE) {
                    // A single short blink every few seconds; idle draws otherwise stay event-driven.
                    val eyeHeight = if (restingEyesClosed) 1f * density else 5f * density
                    canvas.drawRoundRect(12f * density, cy - eyeHeight / 2f, 15f * density, cy + eyeHeight / 2f, density, density, dotPaint)
                    canvas.drawRoundRect(20f * density, cy - eyeHeight / 2f, 23f * density, cy + eyeHeight / 2f, density, density, dotPaint)
                    canvas.drawText(statusText, 34f * density, textY, textPaint)
                    return
                }
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
                    val alpha = (audioLevel * 125f).toInt().coerceIn(0, 125)
                    voiceGlowPaint.color = Color.argb(alpha, 0, 229, 255)
                    val glowWidth = (8f + audioLevel * w * 0.22f).coerceAtMost(w * 0.36f)
                    voiceGlowRect.set(w / 2f - glowWidth, h - 3f * density, w / 2f + glowWidth, h + 2f * density)
                    canvas.drawOval(voiceGlowRect, voiceGlowPaint)
                    canvas.drawCircle(dotCx, cy, dotR + 2f * density, voiceGlowPaint)
                }
                canvas.drawCircle(dotCx, cy, dotR, dotPaint)
                canvas.drawText(statusText, dotCx + 4f * density + dotR + 4 * density, textY, textPaint)
            }

            IslandState.PROCESSING, IslandState.EXECUTING -> {
                // Compact native Thinking Orb: one core and three orbiting particles.
                val centerX = 19f * density
                val orbitR = 6f * density
                val baseAngle = Math.toRadians(spinnerAngle.toDouble())
                val particleColors = ORB_COLORS
                for (i in 0..2) {
                    val angle = baseAngle + i * (Math.PI * 2.0 / 3.0)
                    val x = centerX + kotlin.math.cos(angle).toFloat() * orbitR
                    val y = cy + kotlin.math.sin(angle).toFloat() * orbitR
                    orbPaint.color = particleColors[i]
                    orbGlowPaint.color = Color.argb(90, Color.red(particleColors[i]), Color.green(particleColors[i]), Color.blue(particleColors[i]))
                    canvas.drawCircle(x, y, (2.2f + if (i == 0) 0.4f else 0f) * density, orbGlowPaint)
                    canvas.drawCircle(x, y, (1.4f + if (i == 0) 0.35f else 0f) * density, orbPaint)
                }
                orbPaint.color = ORB_CORE_COLOR
                orbGlowPaint.color = ORB_GLOW_COLOR
                canvas.drawCircle(centerX, cy, 4f * density, orbGlowPaint)
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

    /** A compact, dimensional bot avatar; mouth motion runs only during real TTS playback. */
    private fun drawBotAvatar(canvas: Canvas, cx: Float, cy: Float) {
        val bob = if (speechActive && animationsAllowed) kotlin.math.sin(avatarPhase * 0.5f) * 0.45f * density else 0f
        val faceY = cy + bob
        avatarHeadRect.set(cx - 6f * density, faceY - 6f * density, cx + 6f * density, faceY + 5f * density)
        canvas.drawRoundRect(avatarHeadRect, 4f * density, 4f * density, avatarPaint)
        canvas.drawRoundRect(avatarHeadRect, 4f * density, 4f * density, avatarRimPaint)
        avatarFeaturePaint.color = BOT_EYE_COLOR
        canvas.drawCircle(cx - 2.2f * density, faceY - 1f * density, 1f * density, avatarFeaturePaint)
        canvas.drawCircle(cx + 2.2f * density, faceY - 1f * density, 1f * density, avatarFeaturePaint)
        avatarFeaturePaint.color = BOT_MOUTH_COLOR
        val mouthOpen = if (speechActive && animationsAllowed) (0.5f + 0.5f * kotlin.math.sin(avatarPhase * 2f)).toFloat() else 0f
        val mouthHeight = (0.7f + mouthOpen * 2f) * density
        avatarMouthRect.set(cx - 1.4f * density, faceY + 1.8f * density, cx + 1.4f * density, faceY + 1.8f * density + mouthHeight)
        canvas.drawRoundRect(avatarMouthRect, 1f * density, 1f * density, avatarFeaturePaint)
        avatarFeaturePaint.color = BOT_ACCENT_COLOR
        canvas.drawRect(cx - 0.45f * density, faceY - 8f * density, cx + 0.45f * density, faceY - 5.5f * density, avatarFeaturePaint)
        canvas.drawCircle(cx, faceY - 8f * density, 1f * density, avatarFeaturePaint)
        avatarBodyRect.set(cx - 4f * density, faceY + 5.5f * density, cx + 4f * density, faceY + 8f * density)
        canvas.drawRoundRect(avatarBodyRect, 1.2f * density, 1.2f * density, avatarFeaturePaint)
    }

    private companion object {
        val ORB_COLORS = intArrayOf(
            Color.rgb(255, 171, 0),
            Color.rgb(255, 224, 130),
            Color.rgb(255, 243, 205)
        )
        val ORB_CORE_COLOR = Color.rgb(255, 209, 128)
        val ORB_GLOW_COLOR = Color.argb(136, 255, 171, 0)
        val BOT_EYE_COLOR = Color.rgb(224, 247, 250)
        val BOT_MOUTH_COLOR = Color.rgb(0, 77, 90)
        val BOT_ACCENT_COLOR = Color.rgb(0, 184, 212)
    }

    // ─── Touch ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (expanded) return true
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
                    // Tap opens the in-place information surface.
                    if (!expanded) toggleExpanded()
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
