package com.hid.tabletpen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen touch/stylus capture area.
 * Captures pen input, normalizes coordinates to 0.0–1.0, and provides visual feedback.
 */
class DrawPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class PenEvent(
        val tipDown: Boolean,
        val barrel: Boolean,
        val inRange: Boolean,
        val normalizedX: Float,   // 0.0 – 1.0
        val normalizedY: Float,   // 0.0 – 1.0
        val pressure: Float,      // 0.0 – 1.0
        val toolType: Int
    )

    var onPenEvent: ((PenEvent) -> Unit)? = null

    // Visual feedback
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 100, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 200)
        textSize = 32f
    }

    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var currentPressure = 0f
    private var isDown = false
    private var lastToolType = MotionEvent.TOOL_TYPE_UNKNOWN

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Process historical events for smooth input
        for (i in 0 until event.historySize) {
            processPointer(
                action = MotionEvent.ACTION_MOVE,
                x = event.getHistoricalX(i),
                y = event.getHistoricalY(i),
                pressure = event.getHistoricalPressure(i),
                buttonState = event.buttonState,
                toolType = event.getToolType(0)
            )
        }

        processPointer(
            action = event.actionMasked,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            buttonState = event.buttonState,
            toolType = event.getToolType(0)
        )
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        processPointer(
            action = event.actionMasked,
            x = event.x,
            y = event.y,
            pressure = 0f,
            buttonState = event.buttonState,
            toolType = event.getToolType(0)
        )
        return true
    }

    private fun processPointer(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        buttonState: Int,
        toolType: Int
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val nx = (x / w).coerceIn(0f, 1f)
        val ny = (y / h).coerceIn(0f, 1f)
        val barrel = buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

        cursorX = x
        cursorY = y
        currentPressure = pressure
        lastToolType = toolType

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true
                path.moveTo(x, y)
                lastX = x
                lastY = y
                dispatch(tipDown = true, barrel = barrel, inRange = true, nx, ny, pressure, toolType)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDown) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                }
                dispatch(tipDown = isDown, barrel = barrel, inRange = true, nx, ny, pressure, toolType)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDown = false
                dispatch(tipDown = false, barrel = false, inRange = false, nx, ny, 0f, toolType)
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                dispatch(tipDown = false, barrel = barrel, inRange = true, nx, ny, 0f, toolType)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                cursorX = -1f
                cursorY = -1f
                dispatch(tipDown = false, barrel = false, inRange = false, nx, ny, 0f, toolType)
            }
        }
        invalidate()
    }

    private fun dispatch(
        tipDown: Boolean,
        barrel: Boolean,
        inRange: Boolean,
        nx: Float,
        ny: Float,
        pressure: Float,
        toolType: Int
    ) {
        onPenEvent?.invoke(PenEvent(tipDown, barrel, inRange, nx, ny, pressure, toolType))
    }

    fun clearStrokes() {
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw strokes
        canvas.drawPath(path, strokePaint)

        // Draw cursor crosshair
        if (cursorX >= 0 && cursorY >= 0) {
            val r = 20f + currentPressure * 30f
            canvas.drawCircle(cursorX, cursorY, r, cursorPaint)
            canvas.drawLine(cursorX - r - 5, cursorY, cursorX + r + 5, cursorY, cursorPaint)
            canvas.drawLine(cursorX, cursorY - r - 5, cursorX, cursorY + r + 5, cursorPaint)
        }

        // Draw debug info
        val toolName = when (lastToolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "Stylus"
            MotionEvent.TOOL_TYPE_FINGER -> "Finger"
            MotionEvent.TOOL_TYPE_ERASER -> "Eraser"
            else -> "Unknown"
        }
        val info = "Tool: $toolName  Pressure: ${"%.3f".format(currentPressure)}  " +
                "Pos: (${"%.1f".format(cursorX)}, ${"%.1f".format(cursorY)})"
        canvas.drawText(info, 16f, height - 16f, infoPaint)
    }
}
