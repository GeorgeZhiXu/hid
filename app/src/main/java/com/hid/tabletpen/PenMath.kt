package com.hid.tabletpen

import android.graphics.Bitmap
import android.graphics.Color
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
     * Compute adaptive screenshot quality and max dimension based on recent transfer speed.
     * Returns (jpegQuality 1-100, maxDimension in pixels).
     */
    fun computeAdaptiveQuality(transferMs: Long, transferBytes: Int): Pair<Int, Int> {
        if (transferMs <= 0 || transferBytes <= 0) return Pair(35, 1280) // default
        val bytesPerSec = transferBytes * 1000L / transferMs
        return when {
            bytesPerSec > 500_000 -> Pair(60, 1920)  // WiFi fast: >500KB/s
            bytesPerSec > 100_000 -> Pair(40, 1280)  // WiFi medium: >100KB/s
            else -> Pair(25, 960)                      // BT or slow: <=100KB/s
        }
    }

    /**
     * Resolve quality setting: if AUTO, use adaptive; otherwise use preset values.
     */
    fun resolveQuality(
        quality: CaptureQuality,
        transferMs: Long,
        transferBytes: Int
    ): Pair<Int, Int> {
        return if (quality == CaptureQuality.AUTO) {
            computeAdaptiveQuality(transferMs, transferBytes)
        } else {
            Pair(quality.jpegQuality, quality.maxDim)
        }
    }

    /**
     * Detect whether a bitmap is light or dark by sampling pixel brightness.
     * Returns Color.BLACK for light backgrounds, Color.WHITE for dark.
     */
    fun detectContrastColor(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return Color.WHITE

        val w = bitmap.width
        val h = bitmap.height
        // Sample center + 4 quadrant centers
        val points = listOf(
            Pair(w / 2, h / 2),
            Pair(w / 4, h / 4),
            Pair(3 * w / 4, h / 4),
            Pair(w / 4, 3 * h / 4),
            Pair(3 * w / 4, 3 * h / 4)
        )

        var totalBrightness = 0f
        var count = 0
        for ((x, y) in points) {
            if (x in 0 until w && y in 0 until h) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                totalBrightness += (r + g + b) / 3f
                count++
            }
        }

        val avgBrightness = if (count > 0) totalBrightness / count else 0f
        return if (avgBrightness > 128f) Color.BLACK else Color.WHITE
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
