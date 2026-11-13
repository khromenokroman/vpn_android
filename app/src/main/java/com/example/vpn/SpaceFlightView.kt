package com.example.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** @brief Наклон кольца планеты и орбиты ракеты, градусы. */
private const val ORBIT_TILT_DEG = -18f

/**
 * Космическая сцена: звёздное поле с параллаксом и гиперпрыжком, туманность,
 * планета с кольцом и ракета. Состояния полёта соответствуют состояниям VPN:
 * ракета на площадке (IDLE), взлёт со Stretching-звёздами (LAUNCH), орбита (ORBIT),
 * мерцание без связи (NO_SIGNAL), посадка (LANDING), авария на площадке (ERROR).
 */
class SpaceFlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class FlightState { IDLE, LAUNCH, ORBIT, NO_SIGNAL, LANDING, ERROR }

    private data class Star(
        val x: Float,
        val y: Float,
        val layer: Int,
        val size: Float,
        val phase: Float,
        val color: Int
    )

    var onRocketClick: (() -> Unit)? = null

    private var state = FlightState.IDLE

    /** @brief false, когда в системе отключены анимации: рисуем статичную сцену. */
    private val animationsEnabled: Boolean

    private val stars = ArrayList<Star>()
    private val random = java.util.Random(20261113L)

    // параметры сцены, считаются в onSizeChanged
    private var cx = 0f
    private var cy = 0f
    private var planetRadius = 0f
    private val planetDisk = Path()
    private var rocketWidth = 0f
    private var rocketHeight = 0f

    // накапливающиеся смещения звёзд по слоям (параллакс)
    private val drift = FloatArray(3)

    private var ascend = 0f // 0 - площадка, 1 - улетела за верх экрана
    private var orbitAngle = -Math.PI.toFloat() / 2f
    private var blinkPhase = 0f
    private var lastFrameNs = 0L
    private var frameLoopRunning = false

    private var transitionAnimator: ValueAnimator? = null

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val continentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x59B9C4D6
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebulaPaints = ArrayList<Paint>()
    private val rocketBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EDF5.toInt() }
    private val rocketNosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0455A.toInt() }
    private val rocketFinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0455A.toInt() }
    private val rocketWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FD8F7.toInt() }
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB03A.toInt() }
    private val flameCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE08A.toInt() }

    private val flameRandom = java.util.Random()

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
    }

    fun setState(newState: FlightState) {
        if (state == newState) {
            return
        }
        state = newState
        transitionAnimator?.cancel()

        when (newState) {
            FlightState.LAUNCH -> animateAscend(1f)
            FlightState.LANDING -> animateAscend(0f)
            FlightState.IDLE, FlightState.ERROR -> {
                ascend = 0f
                invalidate()
            }
            FlightState.ORBIT, FlightState.NO_SIGNAL -> invalidate()
        }

        updateFrameLoop()
    }

    private fun animateAscend(target: Float) {
        if (!animationsEnabled) {
            ascend = target
            invalidate()
            return
        }
        transitionAnimator = ValueAnimator.ofFloat(ascend, target).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                ascend = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateFrameLoop() {
        val needLoop = animationsEnabled
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

        // скорость дрейфа: на взлёте звёзды уходят в гиперпрыжок, при посадке тормозят
        val boost = if (state == FlightState.LAUNCH) 1f + 14f * ascend else 1f
        for (layer in 0..2) {
            drift[layer] = (drift[layer] + dt * (6f + layer * 9f) * boost) % width.coerceAtLeast(1)
        }

        if (state == FlightState.ORBIT || state == FlightState.NO_SIGNAL) {
            orbitAngle += dt * 0.7f
        }
        blinkPhase += dt * 4f

        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFrameLoop()
    }

    override fun onDetachedFromWindow() {
        frameLoopRunning = false
        removeCallbacks(frameCallback)
        transitionAnimator?.cancel()
        transitionAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h * 0.58f
        planetRadius = min(w, h) * 0.21f
        planetDisk.reset()
        planetDisk.addCircle(cx, cy, planetRadius, Path.Direction.CW)
        rocketWidth = min(w, h) * 0.075f
        rocketHeight = rocketWidth * 2.6f

        stars.clear()
        val count = (w.toFloat() * h / 6000f).toInt().coerceIn(60, 220)
        for (i in 0 until count) {
            stars.add(
                Star(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    layer = random.nextInt(3),
                    size = starSize(),
                    phase = random.nextFloat() * Math.PI.toFloat() * 2f,
                    color = starColor()
                )
            )
        }

        // мягкая туманность: фиолетовые и синие радиальные градиенты с низкой альфой
        nebulaPaints.clear()
        val nebulas = listOf(
            Triple(w * 0.24f, h * 0.26f, min(w, h) * 0.42f),
            Triple(w * 0.78f, h * 0.16f, min(w, h) * 0.34f),
            Triple(w * 0.66f, h * 0.62f, min(w, h) * 0.5f)
        )
        val nebulaColors = listOf(
            0x2E6E4BD8, // фиолетовый
            0x242E6FD8, // синий
            0x1F4B38C8  // сине-фиолетовый
        )
        for ((nx, ny, nr) in nebulas) {
            nebulaPaints.add(
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(nx, ny, nr, nebulaColors[nebulaPaints.size], 0x00000000, Shader.TileMode.CLAMP)
                }
            )
        }

        // атмосферное свечение планеты: голубой ореол за краем диска
        glowPaint.shader = RadialGradient(
            cx, cy, planetRadius * 1.22f,
            0x00000000, 0x557FD8F7, Shader.TileMode.CLAMP
        )
    }

    /** @brief Размер звезды: три градации, крупные реже. */
    private fun starSize(): Float {
        val roll = random.nextFloat()
        return when {
            roll < 0.6f -> 1.6f + random.nextFloat() * 1.2f
            roll < 0.9f -> 2.8f + random.nextFloat() * 1.2f
            else -> 4f + random.nextFloat() * 1.4f
        }
    }

    /** @brief Цвет звезды: преимущественно белые, часть голубых и жёлтых. */
    private fun starColor(): Int {
        val roll = random.nextFloat()
        return when {
            roll < 0.68f -> 0xFFF4F7FB.toInt()
            roll < 0.86f -> 0xFF9FD8FF.toInt()
            else -> 0xFFFFE08A.toInt()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF070B16.toInt())
        drawNebula(canvas)
        drawStars(canvas)
        // на дальней половине орбиты ракета за планетой, на ближней - перед ней
        if (isOrbiting() && sin(orbitAngle) < 0f) {
            drawRocket(canvas)
            drawPlanet(canvas)
        } else {
            drawPlanet(canvas)
            drawRocket(canvas)
        }
    }

    private fun isOrbiting() = state == FlightState.ORBIT || state == FlightState.NO_SIGNAL

    /**
     * @brief Точка на орбите и направление касательной (градусы) для угла orbitAngle.
     *
     * Орбита - эллипс в плоскости кольца планеты (наклон ORBIT_TILT_DEG): в нижней точке ракета
     * не заходит ниже планеты и не перекрывает кнопку под ней.
     */
    private fun orbitPoint(): Triple<Float, Float, Float> {
        val a = planetRadius * 1.45f
        val b = a * 0.35f
        val t = Math.toRadians(ORBIT_TILT_DEG.toDouble()).toFloat()
        val lx = cos(orbitAngle) * a
        val ly = sin(orbitAngle) * b
        val x = cx + lx * cos(t) - ly * sin(t)
        val y = cy + lx * sin(t) + ly * cos(t)
        val dlx = -sin(orbitAngle) * a
        val dly = cos(orbitAngle) * b
        val dx = dlx * cos(t) - dly * sin(t)
        val dy = dlx * sin(t) + dly * cos(t)
        return Triple(x, y, Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
    }

    private fun drawNebula(canvas: Canvas) {
        for (paint in nebulaPaints) {
            canvas.drawCircle(cx, cy, width.coerceAtLeast(1).toFloat(), paint)
        }
    }

    private fun drawStars(canvas: Canvas) {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val time = System.nanoTime() / 1e9f
        val hyper = if (state == FlightState.LAUNCH) ascend else 0f

        for (star in stars) {
            val layerSpeed = 1f + star.layer * 0.7f
            var sx = star.x - drift[star.layer]
            if (sx < 0f) sx += w
            val sy = star.y

            starPaint.color = star.color

            // мерцание
            val twinkle = 0.55f + 0.45f * sin(time * (1f + star.layer) + star.phase)
            starPaint.alpha = (140 + 115 * twinkle).toInt().coerceIn(0, 255)

            if (hyper > 0.05f) {
                // гиперпрыжок: звезда тянется линией от центра
                val dx = sx - w / 2f
                val dy = sy - h * 0.45f
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val len = hyper * hyper * dist * 0.6f
                val ux = dx / dist
                val uy = dy / dist
                starPaint.strokeWidth = star.size
                starPaint.alpha = (starPaint.alpha * (0.4f + 0.6f * hyper)).toInt()
                canvas.drawLine(sx, sy, sx + ux * len, sy + uy * len, starPaint)
            } else {
                starPaint.strokeWidth = 0f
                canvas.drawCircle(sx, sy, star.size * layerSpeed * 0.6f, starPaint)
            }
        }
    }

    private fun drawPlanet(canvas: Canvas) {
        // кольцо за планетой
        canvas.save()
        canvas.rotate(ORBIT_TILT_DEG, cx, cy)
        ringPaint.strokeWidth = planetRadius * 0.09f
        canvas.drawOval(
            cx - planetRadius * 1.6f, cy - planetRadius * 0.4f,
            cx + planetRadius * 1.6f, cy + planetRadius * 0.4f,
            ringPaint
        )
        canvas.restore()

        // диск планеты: океан, материки, облачные полосы - всё внутри круга
        val disk = planetDisk
        canvas.save()
        canvas.clipPath(disk)

        planetPaint.shader = RadialGradient(
            cx - planetRadius * 0.4f, cy - planetRadius * 0.4f, planetRadius * 1.2f,
            0xFF3D5A8F.toInt(), 0xFF131C33.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, planetRadius, planetPaint)
        planetPaint.shader = null

        // материки
        continentPaint.color = 0xFF2E6B57.toInt()
        canvas.drawOval(
            cx - planetRadius * 0.75f, cy - planetRadius * 0.55f,
            cx + planetRadius * 0.05f, cy - planetRadius * 0.1f,
            continentPaint
        )
        canvas.drawOval(
            cx + planetRadius * 0.05f, cy + planetRadius * 0.05f,
            cx + planetRadius * 0.7f, cy + planetRadius * 0.5f,
            continentPaint
        )
        canvas.drawOval(
            cx - planetRadius * 0.2f, cy + planetRadius * 0.45f,
            cx + planetRadius * 0.35f, cy + planetRadius * 0.75f,
            continentPaint
        )

        // облачные полосы
        val bandH = planetRadius * 0.1f
        for (i in -2..2) {
            val by = cy + i * planetRadius * 0.3f
            canvas.drawRoundRect(
                cx - planetRadius * 0.9f, by,
                cx + planetRadius * 0.9f, by + bandH,
                bandH, bandH, cloudPaint
            )
        }
        canvas.restore()

        // атмосферное свечение по краю
        canvas.drawCircle(cx, cy, planetRadius * 1.22f, glowPaint)
    }

    private fun rocketCenter(): Pair<Float, Float> {
        val padY = cy - planetRadius - rocketHeight * 0.55f
        return when (state) {
            FlightState.ORBIT, FlightState.NO_SIGNAL -> {
                val (x, y, _) = orbitPoint()
                Pair(x, y)
            }
            else -> {
                val x = cx + sin(ascend * Math.PI.toFloat()) * width * 0.06f
                val y = padY - ascend * (padY + rocketHeight)
                Pair(x, y)
            }
        }
    }

    private fun drawRocket(canvas: Canvas) {
        val (rx, ry) = rocketCenter()
        val rotation = when (state) {
            FlightState.ORBIT, FlightState.NO_SIGNAL -> orbitPoint().third
            FlightState.ERROR -> 18f
            else -> 0f
        }
        val blink = if (state == FlightState.NO_SIGNAL) {
            0.35f + 0.65f * abs(sin(blinkPhase))
        } else {
            1f
        }

        val flameOn = state == FlightState.LAUNCH || state == FlightState.ORBIT ||
            state == FlightState.NO_SIGNAL || state == FlightState.LANDING
        val flameScale = if (state == FlightState.LANDING) 0.5f else 1f

        canvas.save()
        canvas.translate(rx, ry)
        canvas.rotate(rotation)
        canvas.scale(1f, blink)

        val w2 = rocketWidth / 2f
        val bodyTop = -rocketHeight / 2f
        val bodyBottom = rocketHeight / 2f

        // пламя за корпусом
        if (flameOn) {
            val flicker = 0.75f + flameRandom.nextFloat() * 0.5f
            val flameLen = rocketHeight * 0.55f * flicker * flameScale
            val flame = Path().apply {
                moveTo(-w2 * 0.55f, bodyBottom)
                lineTo(0f, bodyBottom + flameLen)
                lineTo(w2 * 0.55f, bodyBottom)
                close()
            }
            canvas.drawPath(flame, flamePaint)
            val core = Path().apply {
                moveTo(-w2 * 0.3f, bodyBottom)
                lineTo(0f, bodyBottom + flameLen * 0.6f)
                lineTo(w2 * 0.3f, bodyBottom)
                close()
            }
            canvas.drawPath(core, flameCorePaint)
        }

        // стабилизаторы
        val fin = Path().apply {
            moveTo(-w2, bodyBottom - rocketHeight * 0.28f)
            lineTo(-w2 - rocketWidth * 0.32f, bodyBottom)
            lineTo(-w2, bodyBottom)
            close()
        }
        canvas.drawPath(fin, rocketFinPaint)
        fin.apply {
            reset()
            moveTo(w2, bodyBottom - rocketHeight * 0.28f)
            lineTo(w2 + rocketWidth * 0.32f, bodyBottom)
            lineTo(w2, bodyBottom)
            close()
        }
        canvas.drawPath(fin, rocketFinPaint)

        // корпус с градиентом
        rocketBodyPaint.shader = LinearGradient(
            -w2, 0f, w2, 0f,
            0xFFB9C4D6.toInt(), 0xFFF4F7FB.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            -w2, bodyTop + rocketHeight * 0.18f, w2, bodyBottom,
            w2 * 0.7f, w2 * 0.7f, rocketBodyPaint
        )
        rocketBodyPaint.shader = null

        // носовой конус
        val nose = Path().apply {
            moveTo(-w2, bodyTop + rocketHeight * 0.2f)
            quadTo(0f, bodyTop - rocketHeight * 0.06f, w2, bodyTop + rocketHeight * 0.2f)
            close()
        }
        canvas.drawPath(nose, rocketNosePaint)

        // иллюминатор
        canvas.drawCircle(0f, -rocketHeight * 0.05f, rocketWidth * 0.22f, rocketWindowPaint)
        canvas.drawCircle(0f, -rocketHeight * 0.05f, rocketWidth * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF2A3550.toInt()
        })

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val (rx, ry) = rocketCenter()
            val hitRadius = rocketHeight * 0.9f
            val dx = event.x - rx
            val dy = event.y - ry
            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                performClick()
                onRocketClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
