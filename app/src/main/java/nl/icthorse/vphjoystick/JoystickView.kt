package nl.icthorse.vphjoystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom joystick-pad (Canvas): grote ronde stick die 8 richtingen + release
 * detecteert. Bitmask identiek aan G7K_JOY_* in g7000.h:
 * bit0=UP, bit1=DOWN, bit2=LEFT, bit3=RIGHT. (FIRE = bit4, aparte knop.)
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MASK_UP = 0x01
        const val MASK_DOWN = 0x02
        const val MASK_LEFT = 0x04
        const val MASK_RIGHT = 0x08

        /** Dode zone: binnen 25% van de padstraal telt als "losgelaten". */
        private const val DEAD_ZONE_FRACTION = 0.25f
    }

    /** Callback bij elke verandering van het richtingsmasker (bits 0-3). */
    var onDirectionChanged: ((Int) -> Unit)? = null

    private var directionMask = 0

    // Touch-state (in view-coördinaten, relatief t.o.v. het pad-middelpunt)
    private var knobX = 0f
    private var knobY = 0f
    private var tracking = false

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF23242E.toInt()
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5A623.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B8D9C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val tickActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5A623.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5A623.toInt()
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 4f, Color.BLACK)
    }
    private val knobIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3C4A.toInt()
        style = Paint.Style.FILL
    }

    private val centerX get() = width / 2f
    private val centerY get() = height / 2f
    private val padRadius get() = min(width, height) / 2f * 0.9f
    private val knobRadius get() = padRadius * 0.30f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = centerX
        val cy = centerY
        val r = padRadius

        // Pad + rand
        canvas.drawCircle(cx, cy, r, padPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // 8 richting-streepjes; actieve richting(en) oranje
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val active = sectorMask(i) != 0 && (directionMask and sectorMask(i)) == sectorMask(i)
            val paint = if (active) tickActivePaint else tickPaint
            val inner = r * 0.78f
            val outer = r * 0.92f
            canvas.drawLine(
                cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat(),
                paint
            )
        }

        // Knop
        val kx = cx + knobX
        val ky = cy + knobY
        canvas.drawCircle(kx, ky, knobRadius, if (tracking) knobPaint else knobIdlePaint)
    }

    /** Masker dat hoort bij streepje i (i*45°, 0 = rechts, met de klok mee). */
    private fun sectorMask(i: Int): Int = when (i) {
        0 -> MASK_RIGHT
        1 -> MASK_RIGHT or MASK_DOWN
        2 -> MASK_DOWN
        3 -> MASK_DOWN or MASK_LEFT
        4 -> MASK_LEFT
        5 -> MASK_LEFT or MASK_UP
        6 -> MASK_UP
        7 -> MASK_UP or MASK_RIGHT
        else -> 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                tracking = true
                updateKnob(event.x - centerX, event.y - centerY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                knobX = 0f
                knobY = 0f
                setDirectionMask(0)
                invalidate()
            }
        }
        return true
    }

    private fun updateKnob(dx: Float, dy: Float) {
        val dist = hypot(dx, dy)
        val r = padRadius

        // Knop visueel begrenzen tot de padrand
        if (dist > r) {
            knobX = dx / dist * r
            knobY = dy / dist * r
        } else {
            knobX = dx
            knobY = dy
        }

        val newMask = if (dist < r * DEAD_ZONE_FRACTION) {
            0
        } else {
            // Hoek in graden, 0 = rechts, positief = met de klok mee (y omlaag)
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            maskForAngle(deg)
        }
        setDirectionMask(newMask)
        invalidate()
    }

    /** 8 sectoren van 45°, gecentreerd op de hoofdrichtingen. */
    private fun maskForAngle(deg: Double): Int = when {
        deg >= -22.5 && deg < 22.5 -> MASK_RIGHT
        deg >= 22.5 && deg < 67.5 -> MASK_RIGHT or MASK_DOWN
        deg >= 67.5 && deg < 112.5 -> MASK_DOWN
        deg >= 112.5 && deg < 157.5 -> MASK_DOWN or MASK_LEFT
        deg >= 157.5 || deg < -157.5 -> MASK_LEFT
        deg >= -157.5 && deg < -112.5 -> MASK_LEFT or MASK_UP
        deg >= -112.5 && deg < -67.5 -> MASK_UP
        else -> MASK_UP or MASK_RIGHT // -67.5 .. -22.5
    }

    private fun setDirectionMask(mask: Int) {
        if (mask == directionMask) return
        directionMask = mask
        // Subtiele tik bij elke richtingswissel
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onDirectionChanged?.invoke(mask)
    }
}
