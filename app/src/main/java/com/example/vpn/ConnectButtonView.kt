package com.example.vpn

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** @brief Длительность плавного перехода цветов между состояниями, мс. */
private const val COLOR_TRANSITION_MS = 350L

/** @brief Период «дыхания» свечения подключённого состояния, с. */
private const val BREATH_PERIOD_SEC = 2.6f

/** @brief Период медленного мигания свечения NO_RESPONSE, с. */
private const val BLINK_PERIOD_SEC = 2.2f

/** @brief Скорость вращения дуги подключения, градус/с. */
private const val ARC_SPEED_DEG_SEC = 240f

/** @brief Коэффициент уменьшения кнопки в нажатом состоянии. */
private const val PRESSED_SCALE = 0.95f

/**
 * Круглая кнопка подключения VPN, рисуется на Canvas.
 *
 * Состояния соответствуют состояниям VPN: DISCONNECTED (тусклое кольцо без свечения),
 * CONNECTED (кольцо и значок светятся бирюзовым, пульсирующее «дыхание»), CONNECTING
 * (по кольцу вращается светящаяся дуга), NO_RESPONSE (жёлтое свечение медленно мигает),
 * ERROR (кольцо красноватое, без свечения). Значок питания и подпись под ним рисуются
 * внутри кольца. Переходы цветов плавные, при ANIMATOR_DURATION_SCALE = 0 рисуется
 * статичная картинка без движения.
 */
class ConnectButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ButtonState { DISCONNECTED, CONNECTING, CONNECTED, NO_RESPONSE, ERROR }

    private var state = ButtonState.DISCONNECTED

    /** @brief false, когда в системе отключены анимации: дуга и свечение статичны. */
    private val animationsEnabled: Boolean

    // геометрия, считается в onSizeChanged
    private var cx = 0f
    private var cy = 0f
    private var ringRadius = 0f
    private var glowRadius = 0f
    private val ringRect = RectF()
    private val arcRect = RectF()
    private val iconRect = RectF()

    // текущие отображаемые значения (меняются переходом)
    private var ringColor = 0xFF4A5568.toInt()
    private var iconColor = 0xFF8B98AD.toInt()
    private var glowColor = 0x002FE6A7
    private var glowStrength = 0f
    private var rotationDeg = 0f
    private var phaseSec = 0f
    private var lastFrameNs = 0L
    private var pressedScale = 1f
    private var frameLoopRunning = false

    private var transitionAnimator: ValueAnimator? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121A2A.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FB.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val powerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val frameCallback = object : Runnable {
        override fun run() {
            stepFrame()
            if (frameLoopRunning) {
                postOnAnimation(this)
            }
        }
    }

    init {
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (e: Exception) {
            1f
        }
        animationsEnabled = scale > 0f
        // кисти по умолчанию чёрные: без этого начальное состояние рисовалось бы чёрным кольцом
        applyColors()
    }

    fun setState(newState: ButtonState) {
        if (state == newState) {
            return
        }
        state = newState
        startColorTransition()
        updateFrameLoop()
        invalidate()
    }

    /** @brief Останавливает фрейм-цикл и переход цветов (activity ушла в паузу). */
    fun pauseAnimations() {
        frameLoopRunning = false
        removeCallbacks(frameCallback)
        transitionAnimator?.cancel()
        transitionAnimator = null
        // переход прерван - сразу целевые цвета, иначе кнопка застынет в промежуточном цвете
        val (ring, icon, glow) = targetColors()
        ringColor = ring
        iconColor = icon
        glowColor = glow
        glowStrength = if (glow ushr 24 == 0) 0f else 1f
        applyColors()
        invalidate()
    }

    /** @brief Возобновляет анимации, если текущему состоянию нужен фрейм-цикл. */
    fun resumeAnimations() {
        updateFrameLoop()
    }

    private fun targetColors(): Triple<Int, Int, Int> = when (state) {
        ButtonState.DISCONNECTED -> Triple(0xFF4A5568.toInt(), 0xFF8B98AD.toInt(), 0x002FE6A7)
        ButtonState.CONNECTING -> Triple(0xFF4A5568.toInt(), 0xFF7FD8F7.toInt(), 0x337FD8F7)
        ButtonState.CONNECTED -> Triple(0xFF2FE6A7.toInt(), 0xFF7DF5C8.toInt(), 0x402FE6A7)
        ButtonState.NO_RESPONSE -> Triple(0xFFFFC94D.toInt(), 0xFFFFE08A.toInt(), 0x40FFC94D)
        ButtonState.ERROR -> Triple(0xFFE0455A.toInt(), 0xFFF08A96.toInt(), 0x00E0455A)
    }

    /** @brief Нужен ли фрейм-цикл текущему состоянию. */
    private fun needsFrameLoop() = state == ButtonState.CONNECTING ||
        state == ButtonState.CONNECTED || state == ButtonState.NO_RESPONSE

    private fun startColorTransition() {
        transitionAnimator?.cancel()
        val (targetRing, targetIcon, targetGlow) = targetColors()
        val targetStrength = if (targetGlow ushr 24 == 0) 0f else 1f
        val startRing = ringColor
        val startIcon = iconColor
        val startGlow = glowColor
        val startStrength = glowStrength
        val evaluator = ArgbEvaluator()

        if (!animationsEnabled) {
            ringColor = targetRing
            iconColor = targetIcon
            glowColor = targetGlow
            glowStrength = targetStrength
            applyColors()
            return
        }

        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_TRANSITION_MS
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                ringColor = evaluator.evaluate(f, startRing, targetRing) as Int
                iconColor = evaluator.evaluate(f, startIcon, targetIcon) as Int
                glowColor = evaluator.evaluate(f, startGlow, targetGlow) as Int
                glowStrength = startStrength + (targetStrength - startStrength) * f
                applyColors()
                invalidate()
            }
            start()
        }
    }

    /** @brief Переносит текущие цвета в кисти и пересобирает шейдер свечения. */
    private fun applyColors() {
        ringPaint.color = ringColor
        arcPaint.color = iconColor
        iconPaint.color = iconColor
        labelPaint.color = iconColor
        buildGlowShader()
    }

    private fun updateFrameLoop() {
        val needLoop = animationsEnabled && needsFrameLoop() && isAttachedToWindow
        if (needLoop && !frameLoopRunning) {
            frameLoopRunning = true
            lastFrameNs = System.nanoTime()
            postOnAnimation(frameCallback)
        } else if (!needLoop && frameLoopRunning) {
            frameLoopRunning = false
            removeCallbacks(frameCallback)
        }
    }

    private fun stepFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now
        phaseSec += dt
        if (state == ButtonState.CONNECTING) {
            rotationDeg = (rotationDeg + dt * ARC_SPEED_DEG_SEC) % 360f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFrameLoop()
    }

    override fun onDetachedFromWindow() {
        pauseAnimations()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        ringRadius = min(w, h) / 2f * 0.66f
        glowRadius = min(w, h) / 2f * 0.98f
        val ringWidth = ringRadius * 0.055f
        ringPaint.strokeWidth = ringWidth
        arcPaint.strokeWidth = ringWidth * 2.4f
        powerStrokePaint.strokeWidth = ringRadius * 0.075f
        iconPaint.strokeWidth = ringRadius * 0.075f
        labelPaint.textSize = ringRadius * 0.17f

        ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        val arcRadius = ringRadius - ringWidth * 1.6f
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        val iconR = ringRadius * 0.30f
        iconRect.set(cx - iconR, cy - ringRadius * 0.10f - iconR, cx + iconR, cy - ringRadius * 0.10f + iconR)
        buildGlowShader()
    }

    private fun buildGlowShader() {
        if (width == 0 || height == 0) {
            return
        }
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            glowColor, glowColor and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(pressedScale, pressedScale, cx, cy)

        // пульсирующее или мигающее свечение
        glowPaint.alpha = (255 * glowStrength * glowPulse()).toInt()
        if (glowPaint.alpha > 0) {
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        }

        canvas.drawCircle(cx, cy, ringRadius * 0.94f, discPaint)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)

        if (state == ButtonState.CONNECTING) {
            canvas.save()
            canvas.rotate(rotationDeg, cx, cy)
            canvas.drawArc(arcRect, -50f, 110f, false, arcPaint)
            canvas.restore()
        }

        drawPowerIcon(canvas)
        drawLabel(canvas)
        canvas.restore()
    }

    /** @brief Множитель яркости свечения: «дыхание» или медленное мигание. */
    private fun glowPulse(): Float = when (state) {
        ButtonState.CONNECTED ->
            0.55f + 0.45f * sin(phaseSec * 2f * Math.PI.toFloat() / BREATH_PERIOD_SEC)
        ButtonState.NO_RESPONSE ->
            0.25f + 0.75f * sin(phaseSec * Math.PI.toFloat() / BLINK_PERIOD_SEC)
        else -> 1f
    }

    /** @brief Значок питания: дуга с разрывом сверху и вертикальный штрих. */
    private fun drawPowerIcon(canvas: Canvas) {
        val iconR = ringRadius * 0.30f
        val iconCy = cy - ringRadius * 0.10f
        canvas.drawArc(iconRect, -50f, 280f, false, iconPaint)
        canvas.drawLine(
            cx, iconCy - iconR - iconR * 0.35f,
            cx, iconCy - iconR * 0.28f,
            iconPaint
        )
    }

    private fun drawLabel(canvas: Canvas) {
        val label = when (state) {
            ButtonState.DISCONNECTED, ButtonState.ERROR ->
                context.getString(R.string.connect_label_start)
            ButtonState.CONNECTED, ButtonState.NO_RESPONSE ->
                context.getString(R.string.connect_label_stop)
            ButtonState.CONNECTING ->
                context.getString(R.string.connect_label_cancel)
        }
        val y = cy + ringRadius * 0.48f + labelPaint.textSize * 0.35f
        canvas.drawText(label, cx, y, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedScale = PRESSED_SCALE
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedScale = 1f
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedScale = 1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
