package com.hid.tabletpen

import android.content.Context

enum class InputMode { DIGITIZER, MOUSE }

enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

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
    val mouseSensitivity: Float = 2.0f
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
        private const val KEY_LAST_DEVICE = "last_device"

        fun loadLastDevice(context: Context): String? {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE, null)
        }

        fun saveLastDevice(context: Context, address: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_DEVICE, address)
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
                mouseSensitivity = p.getFloat(KEY_MOUSE_SENS, 2.0f)
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
                .apply()
        }
    }
}
