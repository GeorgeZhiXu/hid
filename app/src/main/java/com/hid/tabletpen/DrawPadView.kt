package com.hid.tabletpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Data classes for callbacks ----

    data class PenEvent(
        val tipDown: Boolean,
        val barrel: Boolean,
        val inRange: Boolean,
        val normalizedX: Float,
        val normalizedY: Float,
        val pressure: Float,
        val toolType: Int
    )

    data class MouseEvent(
        val leftButton: Boolean,
        val rightButton: Boolean,
        val dx: Float,
        val dy: Float,
        val scroll: Float = 0f
    )

    var onPenEvent: ((PenEvent) -> Unit)? = null
    var onMouseEvent: ((MouseEvent) -> Unit)? = null

    // ---- Settings ----

    var inputMode: InputMode = InputMode.DIGITIZER

    var targetAspectRatio: Float = 16f / 10f
        set(value) {
            field = value
            if (focusRect == null) originalAspectRatio = value
            recomputeActiveArea()
            invalidate()
        }

    var transparentMode: Boolean = false
        set(value) {
            field = value
            setBackgroundColor(if (value) Color.TRANSPARENT else Color.parseColor("#0F3460"))
        }

    /** The full screenshot bitmap (unzoomed) */
    private var fullBitmap: Bitmap? = null

    /** The bitmap actually displayed (may be cropped if focused) */
    var backgroundBitmap: Bitmap? = null
        private set

    /** Set a new screenshot. Applies focus crop if active. */
    fun setScreenshot(bitmap: Bitmap) {
        fullBitmap = bitmap
        backgroundBitmap = applyFocusCrop(bitmap)
        invalidate()
    }

    // ---- Focus mode ----

    /** Normalized focus rect (0-1) relative to the full screenshot. Null = no focus. */
    var focusRect: RectF? = null
        private set

    /** True when user is selecting a focus area */
    var focusSelecting: Boolean = false

    private var focusStartX = 0f
    private var focusStartY = 0f
    private var focusCurrentRect: RectF? = null // selection in progress (view coords)

    private val focusSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 200, 50)
        style = Paint.Style.FILL
    }
    private val focusSelectBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 50)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Saved original aspect ratio to restore on focus reset */
    private var originalAspectRatio: Float = 16f / 10f

    /** Call when user confirms the focus selection */
    fun confirmFocusSelection() {
        val sel = focusCurrentRect ?: return
        if (sel.width() < 10 || sel.height() < 10) return

        // Convert view coords to normalized (0-1) within the active rect
        val nx1 = ((sel.left - activeRect.left) / activeRect.width()).coerceIn(0f, 1f)
        val ny1 = ((sel.top - activeRect.top) / activeRect.height()).coerceIn(0f, 1f)
        val nx2 = ((sel.right - activeRect.left) / activeRect.width()).coerceIn(0f, 1f)
        val ny2 = ((sel.bottom - activeRect.top) / activeRect.height()).coerceIn(0f, 1f)

        val fw = nx2 - nx1
        val fh = ny2 - ny1
        if (fw <= 0 || fh <= 0) return

        // Use the selection as-is — its aspect ratio becomes the new drawing area ratio
        focusRect = RectF(nx1, ny1, nx2, ny2)

        // Save original and update drawing area aspect ratio to match selection
        if (originalAspectRatio == targetAspectRatio) {
            originalAspectRatio = targetAspectRatio
        }
        // The selection's aspect ratio in screen space:
        // selection covers fw of screen width and fh of screen height
        // screen aspect ratio is targetAspectRatio (w/h), so actual selection ratio is:
        targetAspectRatio = (fw * originalAspectRatio) / fh

        android.util.Log.i("Focus", "focusRect=$focusRect ratio=$targetAspectRatio")

        focusSelecting = false
        focusCurrentRect = null

        // Re-crop the screenshot and recompute drawing area
        fullBitmap?.let {
            backgroundBitmap = applyFocusCrop(it)
            android.util.Log.i("Focus", "Cropped: ${backgroundBitmap?.width}x${backgroundBitmap?.height}")
        }
        invalidate()
    }

    fun resetFocus() {
        focusRect = null
        focusCurrentRect = null
        focusSelecting = false
        targetAspectRatio = originalAspectRatio
        fullBitmap?.let { backgroundBitmap = it }
        invalidate()
    }

    private fun applyFocusCrop(bitmap: Bitmap): Bitmap {
        val fr = focusRect ?: return bitmap
        val x = (fr.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (fr.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = (fr.width() * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val h = (fr.height() * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    // ---- Active area (aspect-ratio constrained) ----

    private var activeRect = RectF()

    // ---- Visual feedback paints ----

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val strokeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
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

    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 100, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val outsidePaint = Paint().apply {
        color = Color.argb(60, 0, 0, 0)
    }

    // ---- Drawing state ----

    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var currentPressure = 0f
    private var isDown = false
    private var lastToolType = MotionEvent.TOOL_TYPE_UNKNOWN

    // ---- Mouse mode state ----

    private var lastMouseX = -1f
    private var lastMouseY = -1f
    private var mouseButtonDown = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val LONG_PRESS_TIMEOUT = 400L
    private val LONG_PRESS_MOVE_THRESHOLD = 25f // px squared

    // ---- Lifecycle ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeActiveArea()
    }

    private fun recomputeActiveArea() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        val viewRatio = viewW / viewH
        val targetRatio = targetAspectRatio

        val rectW: Float
        val rectH: Float
        if (viewRatio > targetRatio) {
            rectH = viewH
            rectW = viewH * targetRatio
        } else {
            rectW = viewW
            rectH = viewW / targetRatio
        }

        val left = (viewW - rectW) / 2f
        val top = (viewH - rectH) / 2f
        activeRect.set(left, top, left + rectW, top + rectH)
    }

    fun clearStrokes() {
        path.reset()
        invalidate()
    }

    // ---- Touch/hover event handling ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        action: Int, x: Float, y: Float, pressure: Float,
        buttonState: Int, toolType: Int
    ) {
        if (activeRect.isEmpty) return

        cursorX = x
        cursorY = y
        currentPressure = pressure
        lastToolType = toolType

        // Focus selection mode: any touch draws a selection rectangle
        if (focusSelecting) {
            processFocusSelection(action, x, y)
            invalidate()
            return
        }

        when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                when (inputMode) {
                    InputMode.DIGITIZER -> processDigitizer(action, x, y, pressure, buttonState, toolType)
                    InputMode.MOUSE -> processMouse(action, x, y, buttonState)
                }
            }
            MotionEvent.TOOL_TYPE_FINGER -> {
                processFingerMouse(action, x, y)
            }
        }

        invalidate()
    }

    private fun processFocusSelection(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                focusStartX = x
                focusStartY = y
                focusCurrentRect = RectF(x, y, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                focusCurrentRect = RectF(
                    minOf(focusStartX, x), minOf(focusStartY, y),
                    maxOf(focusStartX, x), maxOf(focusStartY, y)
                )
            }
            MotionEvent.ACTION_UP -> {
                focusCurrentRect = RectF(
                    minOf(focusStartX, x), minOf(focusStartY, y),
                    maxOf(focusStartX, x), maxOf(focusStartY, y)
                )
                confirmFocusSelection()
            }
        }
    }

    // ---- Digitizer mode ----

    private fun processDigitizer(
        action: Int, x: Float, y: Float, pressure: Float,
        buttonState: Int, toolType: Int
    ) {
        val nx = ((x - activeRect.left) / activeRect.width()).coerceIn(0f, 1f)
        val ny = ((y - activeRect.top) / activeRect.height()).coerceIn(0f, 1f)
        val barrel = buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true
                path.moveTo(x, y)
                lastX = x
                lastY = y
                dispatchPen(tipDown = true, barrel = barrel, inRange = true, nx, ny, pressure, toolType)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDown) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                }
                dispatchPen(tipDown = isDown, barrel = barrel, inRange = true, nx, ny, pressure, toolType)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDown = false
                dispatchPen(tipDown = false, barrel = false, inRange = false, nx, ny, 0f, toolType)
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                dispatchPen(tipDown = false, barrel = barrel, inRange = true, nx, ny, 0f, toolType)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                cursorX = -1f
                cursorY = -1f
                dispatchPen(tipDown = false, barrel = false, inRange = false, nx, ny, 0f, toolType)
            }
        }
    }

    private fun dispatchPen(
        tipDown: Boolean, barrel: Boolean, inRange: Boolean,
        nx: Float, ny: Float, pressure: Float, toolType: Int
    ) {
        onPenEvent?.invoke(PenEvent(tipDown, barrel, inRange, nx, ny, pressure, toolType))
    }

    // ---- Mouse mode ----

    private fun processMouse(action: Int, x: Float, y: Float, buttonState: Int) {
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                lastMouseX = x
                lastMouseY = y
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (lastMouseX >= 0 && lastMouseY >= 0) {
                    val dx = x - lastMouseX
                    val dy = y - lastMouseY
                    onMouseEvent?.invoke(MouseEvent(leftButton = false, rightButton = false, dx = dx, dy = dy))
                }
                lastMouseX = x
                lastMouseY = y
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastMouseX = -1f
                lastMouseY = -1f
            }
            MotionEvent.ACTION_DOWN -> {
                mouseButtonDown = true
                longPressTriggered = false
                lastMouseX = x
                lastMouseY = y

                longPressRunnable = Runnable {
                    longPressTriggered = true
                    // Send right-click press
                    onMouseEvent?.invoke(MouseEvent(leftButton = false, rightButton = true, dx = 0f, dy = 0f))
                }
                longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT)

                // Send left button down
                onMouseEvent?.invoke(MouseEvent(leftButton = true, rightButton = false, dx = 0f, dy = 0f))
            }
            MotionEvent.ACTION_MOVE -> {
                if (mouseButtonDown && lastMouseX >= 0 && lastMouseY >= 0) {
                    val dx = x - lastMouseX
                    val dy = y - lastMouseY
                    if (dx * dx + dy * dy > LONG_PRESS_MOVE_THRESHOLD) {
                        cancelPendingLongPress()
                    }
                    val button = !longPressTriggered
                    onMouseEvent?.invoke(MouseEvent(leftButton = button, rightButton = longPressTriggered, dx = dx, dy = dy))
                    lastMouseX = x
                    lastMouseY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                mouseButtonDown = false
                // Release all buttons
                onMouseEvent?.invoke(MouseEvent(leftButton = false, rightButton = false, dx = 0f, dy = 0f))
                lastMouseX = -1f
                lastMouseY = -1f
            }
        }
    }

    // ---- Finger → mouse move only (no clicks, no taps) ----

    private var lastFingerX = -1f
    private var lastFingerY = -1f

    private fun processFingerMouse(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Just record position, don't send anything (no click)
                lastFingerX = x
                lastFingerY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (lastFingerX >= 0 && lastFingerY >= 0) {
                    val dx = x - lastFingerX
                    val dy = y - lastFingerY
                    // Only send movement if finger actually moved (ignore jitter)
                    if (dx * dx + dy * dy > 4f) {
                        onMouseEvent?.invoke(MouseEvent(leftButton = false, rightButton = false, dx = dx, dy = dy))
                        lastFingerX = x
                        lastFingerY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Just reset, don't send any click
                lastFingerX = -1f
                lastFingerY = -1f
            }
        }
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background screenshot in active area
        backgroundBitmap?.let { bmp ->
            if (!activeRect.isEmpty) {
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = Rect(activeRect.left.toInt(), activeRect.top.toInt(),
                    activeRect.right.toInt(), activeRect.bottom.toInt())
                canvas.drawBitmap(bmp, src, dst, null)
            }
        }

        // Draw dimmed regions outside active area
        if (!activeRect.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), activeRect.top, outsidePaint)
            canvas.drawRect(0f, activeRect.bottom, width.toFloat(), height.toFloat(), outsidePaint)
            canvas.drawRect(0f, activeRect.top, activeRect.left, activeRect.bottom, outsidePaint)
            canvas.drawRect(activeRect.right, activeRect.top, width.toFloat(), activeRect.bottom, outsidePaint)
            canvas.drawRect(activeRect, boundaryPaint)
        }

        // Draw strokes
        if (transparentMode) {
            canvas.drawPath(path, strokeShadowPaint)
        }
        canvas.drawPath(path, strokePaint)

        // Draw cursor crosshair
        if (cursorX >= 0 && cursorY >= 0) {
            val r = 20f + currentPressure * 30f
            canvas.drawCircle(cursorX, cursorY, r, cursorPaint)
            canvas.drawLine(cursorX - r - 5, cursorY, cursorX + r + 5, cursorY, cursorPaint)
            canvas.drawLine(cursorX, cursorY - r - 5, cursorX, cursorY + r + 5, cursorPaint)
        }

        // Draw focus selection rectangle
        focusCurrentRect?.let { sel ->
            canvas.drawRect(sel, focusSelectPaint)
            canvas.drawRect(sel, focusSelectBorderPaint)
        }

        // Draw debug info
        val focusLabel = if (focusRect != null) " | FOCUS" else ""
        val selectLabel = if (focusSelecting) " | SELECT AREA" else ""
        val toolName = when (lastToolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "Stylus"
            MotionEvent.TOOL_TYPE_FINGER -> "Finger"
            MotionEvent.TOOL_TYPE_ERASER -> "Eraser"
            else -> "Unknown"
        }
        val modeName = if (inputMode == InputMode.DIGITIZER) "Pen" else "Mouse"
        val info = "$modeName | $toolName | P: ${"%.3f".format(currentPressure)}$focusLabel$selectLabel"
        canvas.drawText(info, 16f, height - 16f, infoPaint)
    }
}
