package com.hid.tabletpen

import android.graphics.RectF
import kotlin.math.pow

/**
 * Pure math functions extracted for testability.
 * No Android framework dependencies beyond RectF.
 */
object PenMath {

    /**
     * Pressure curve: floor guarantees minimum pressure when tip is down,
     * remaining range is scaled by a power curve.
     */
    fun calculatePressure(
        tipDown: Boolean,
        rawPressure: Float,
        floor: Float,
        exponent: Float,
        pressureMax: Int
    ): Int {
        if (!tipDown) return 0
        val curved = rawPressure.toDouble().pow(exponent.toDouble()).toFloat()
        return ((floor + curved * (1f - floor)) * pressureMax).toInt()
    }

    /**
     * Break large mouse deltas into HID-compatible chunks of [-127, 127].
     * Returns list of (dx, dy) pairs that sum to the original values.
     */
    fun chunkMouseDeltas(dx: Int, dy: Int, maxDelta: Int = 127): List<Pair<Int, Int>> {
        val chunks = mutableListOf<Pair<Int, Int>>()
        var rx = dx
        var ry = dy
        do {
            val sx = rx.coerceIn(-maxDelta, maxDelta)
            val sy = ry.coerceIn(-maxDelta, maxDelta)
            chunks.add(Pair(sx, sy))
            rx -= sx
            ry -= sy
        } while (rx != 0 || ry != 0)
        return chunks
    }

    /**
     * Map normalized coordinates (0-1) through an optional focus rectangle.
     * If focusRect is null, returns input unchanged.
     */
    fun mapThroughFocus(nx: Float, ny: Float, focusRect: RectF?): Pair<Float, Float> {
        if (focusRect == null) return Pair(nx, ny)
        val mappedX = focusRect.left + nx * focusRect.width()
        val mappedY = focusRect.top + ny * focusRect.height()
        return Pair(mappedX, mappedY)
    }

    /**
     * Compute the aspect-ratio-constrained active drawing area within a view.
     * Returns a centered RectF that fits within (viewW x viewH) with targetRatio.
     */
    fun computeActiveRect(viewW: Float, viewH: Float, targetRatio: Float): RectF {
        if (viewW <= 0 || viewH <= 0) return RectF()
        val viewRatio = viewW / viewH
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
        return RectF(left, top, left + rectW, top + rectH)
    }

    /**
     * Parse "wifi:host:port" line from Mac server.
     * Returns (host, port) or null if invalid.
     */
    fun parseWifiInfo(line: String): Pair<String, Int>? {
        if (!line.startsWith("wifi:")) return null
        val parts = line.removePrefix("wifi:").split(":")
        if (parts.size != 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        if (port <= 0) return null
        return Pair(host, port)
    }
}
