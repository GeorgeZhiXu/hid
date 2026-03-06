package com.hid.tabletpen

import android.content.Context
import org.json.JSONObject

enum class InputMode { DIGITIZER, MOUSE }

enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

enum class StrokeColor {
    AUTO,       // detect from screenshot brightness
    WHITE,
    BLACK,
    RED,
    BLUE;

    companion object {
        val LABELS = listOf("Auto", "White", "Black", "Red", "Blue")
    }
}

enum class CursorStyle {
    NONE,       // invisible
    CROSSHAIR,  // current default: circle + crosshair lines
    DOT,        // small solid dot
    CIRCLE;     // small hollow circle

    companion object {
        val LABELS = listOf("None", "Crosshair", "Dot", "Circle")
    }
}

data class AspectRatio(val w: Int, val h: Int) {
    val ratio: Float get() = w.toFloat() / h.toFloat()
    override fun toString(): String = "$w:$h"

    companion object {
        val RATIO_16_9 = AspectRatio(16, 9)
        val RATIO_16_10 = AspectRatio(16, 10)
        val RATIO_3_2 = AspectRatio(3, 2)
        val PRESETS = listOf(RATIO_16_10, RATIO_16_9, RATIO_3_2)
    }
}

data class AppSettings(
    val inputMode: InputMode = InputMode.DIGITIZER,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val rotationDegrees: Int = 0,
    val targetAspectRatio: AspectRatio = AspectRatio.RATIO_16_10,
    val clearOnScreenshot: Boolean = true,   // auto-clear strokes on new screenshot
    val pressureFloor: Float = 0.8f,       // min pressure when tip is down (0.0–1.0)
    val pressureExponent: Float = 0.5f,    // curve exponent for remaining range
    val mouseSensitivity: Float = 2.0f,
    val scrollSensitivity: Float = 2.0f,    // scroll speed multiplier (0.5–5.0)
    val pinchSensitivity: Float = 30f,      // pinch zoom multiplier (5–60)
    val pinchThreshold: Float = 0.01f,      // min distance-change ratio to trigger pinch (0.005–0.05)
    val cursorStyle: CursorStyle = CursorStyle.CROSSHAIR,
    val strokeColor: StrokeColor = StrokeColor.AUTO,
    val autoRecapture: Boolean = false
) {
    companion object {
        private const val PREFS = "tabletpen_settings"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_ROTATION = "rotation"
        private const val KEY_RATIO_W = "ratio_w"
        private const val KEY_RATIO_H = "ratio_h"
        private const val KEY_CLEAR_ON_SS = "clear_on_screenshot"
        private const val KEY_PRESSURE_FLOOR = "pressure_floor"
        private const val KEY_PRESSURE_EXP = "pressure_exp"
        private const val KEY_MOUSE_SENS = "mouse_sens"
        private const val KEY_SCROLL_SENS = "scroll_sens"
        private const val KEY_PINCH_SENS = "pinch_sens"
        private const val KEY_PINCH_THRESH = "pinch_thresh"
        private const val KEY_CURSOR_STYLE = "cursor_style"
        private const val KEY_STROKE_COLOR = "stroke_color"
        private const val KEY_AUTO_RECAPTURE = "auto_recapture"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_KNOWN_DEVICES = "known_devices"

        fun loadLastDevice(context: Context): String? {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE, null)
        }

        fun saveLastDevice(context: Context, address: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_DEVICE, address)
                .apply()
        }

        /** Load known devices as address → name map */
        fun loadKnownDevices(context: Context): LinkedHashMap<String, String> {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_KNOWN_DEVICES, null) ?: return linkedMapOf()
            return try {
                val obj = JSONObject(json)
                val map = linkedMapOf<String, String>()
                obj.keys().forEach { key -> map[key] = obj.getString(key) }
                map
            } catch (_: Exception) {
                linkedMapOf()
            }
        }

        /** Add or update a known device */
        fun saveKnownDevice(context: Context, address: String, name: String) {
            val devices = loadKnownDevices(context)
            devices[address] = name
            val obj = JSONObject()
            devices.forEach { (k, v) -> obj.put(k, v) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_KNOWN_DEVICES, obj.toString())
                .apply()
        }

        /** Remove a known device */
        fun removeKnownDevice(context: Context, address: String) {
            val devices = loadKnownDevices(context)
            devices.remove(address)
            val obj = JSONObject()
            devices.forEach { (k, v) -> obj.put(k, v) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_KNOWN_DEVICES, obj.toString())
                .apply()
        }

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                inputMode = InputMode.entries.getOrElse(p.getInt(KEY_INPUT_MODE, 0)) { InputMode.DIGITIZER },
                orientationMode = OrientationMode.entries.getOrElse(p.getInt(KEY_ORIENTATION, 0)) { OrientationMode.AUTO },
                rotationDegrees = p.getInt(KEY_ROTATION, 0),
                targetAspectRatio = AspectRatio(p.getInt(KEY_RATIO_W, 16), p.getInt(KEY_RATIO_H, 10)),
                clearOnScreenshot = p.getBoolean(KEY_CLEAR_ON_SS, true),
                pressureFloor = p.getFloat(KEY_PRESSURE_FLOOR, 0.8f),
                pressureExponent = p.getFloat(KEY_PRESSURE_EXP, 0.5f),
                mouseSensitivity = p.getFloat(KEY_MOUSE_SENS, 2.0f),
                scrollSensitivity = p.getFloat(KEY_SCROLL_SENS, 2.0f),
                pinchSensitivity = p.getFloat(KEY_PINCH_SENS, 30f),
                pinchThreshold = p.getFloat(KEY_PINCH_THRESH, 0.01f),
                cursorStyle = CursorStyle.entries.getOrElse(p.getInt(KEY_CURSOR_STYLE, 1)) { CursorStyle.CROSSHAIR },
                strokeColor = StrokeColor.entries.getOrElse(p.getInt(KEY_STROKE_COLOR, 0)) { StrokeColor.AUTO },
                autoRecapture = p.getBoolean(KEY_AUTO_RECAPTURE, false)
            )
        }

        fun save(context: Context, s: AppSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_INPUT_MODE, s.inputMode.ordinal)
                .putInt(KEY_ORIENTATION, s.orientationMode.ordinal)
                .putInt(KEY_ROTATION, s.rotationDegrees)
                .putInt(KEY_RATIO_W, s.targetAspectRatio.w)
                .putInt(KEY_RATIO_H, s.targetAspectRatio.h)
                .putBoolean(KEY_CLEAR_ON_SS, s.clearOnScreenshot)
                .putFloat(KEY_PRESSURE_FLOOR, s.pressureFloor)
                .putFloat(KEY_PRESSURE_EXP, s.pressureExponent)
                .putFloat(KEY_MOUSE_SENS, s.mouseSensitivity)
                .putFloat(KEY_SCROLL_SENS, s.scrollSensitivity)
                .putFloat(KEY_PINCH_SENS, s.pinchSensitivity)
                .putFloat(KEY_PINCH_THRESH, s.pinchThreshold)
                .putInt(KEY_CURSOR_STYLE, s.cursorStyle.ordinal)
                .putInt(KEY_STROKE_COLOR, s.strokeColor.ordinal)
                .putBoolean(KEY_AUTO_RECAPTURE, s.autoRecapture)
                .apply()
        }
    }
}
