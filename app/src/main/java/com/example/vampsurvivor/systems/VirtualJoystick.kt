package com.example.vampsurvivor.systems

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class VirtualJoystick(
    private val basePaint: Paint,
    private val knobPaint: Paint,
    private val baseRadius: Float,
    private val knobRadius: Float
) {
    private var pointerId: Int = -1
    private var baseX: Float = 0f
    private var baseY: Float = 0f
    private var knobX: Float = 0f
    private var knobY: Float = 0f
    var normalizedX: Float = 0f
        private set
    var normalizedY: Float = 0f
        private set

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId == -1) {
                    val index = event.actionIndex
                    pointerId = event.getPointerId(index)
                    baseX = event.getX(index)
                    baseY = event.getY(index)
                    knobX = baseX
                    knobY = baseY
                    normalizedX = 0f
                    normalizedY = 0f
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerId != -1) {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        val dx = event.getX(index) - baseX
                        val dy = event.getY(index) - baseY
                        val distance = hypot(dx, dy)
                        val clamped = min(distance, baseRadius)
                        val angle = atan2(dy, dx)
                        knobX = baseX + cos(angle) * clamped
                        knobY = baseY + sin(angle) * clamped
                        normalizedX = (knobX - baseX) / baseRadius
                        normalizedY = (knobY - baseY) / baseRadius
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId != -1 && pointerId == event.getPointerId(event.actionIndex)) {
                    reset()
                }
            }
        }
        return false
    }

    fun draw(canvas: Canvas) {
        if (pointerId == -1) return
        canvas.drawCircle(baseX, baseY, baseRadius, basePaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    fun reset() {
        pointerId = -1
        normalizedX = 0f
        normalizedY = 0f
    }
}
