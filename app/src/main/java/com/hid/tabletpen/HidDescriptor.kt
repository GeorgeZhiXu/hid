package com.hid.tabletpen

/**
 * HID Report Descriptors for digitizer pen and mouse.
 *
 * Report ID 1 — Digitizer (7 bytes):
 *   Byte 0: [0:Tip Switch][1:Barrel Switch][2:In Range][3:Eraser][4-7:padding]
 *   Byte 1-2: X (uint16 LE, 0–32767)
 *   Byte 3-4: Y (uint16 LE, 0–32767)
 *   Byte 5-6: Pressure (uint16 LE, 0–4095)
 *
 * Report ID 2 — Mouse (4 bytes):
 *   Byte 0: [0:Left][1:Right][2:Middle][3-7:padding]
 *   Byte 1: X delta (int8, -127..127)
 *   Byte 2: Y delta (int8, -127..127)
 *   Byte 3: Scroll wheel (int8, -127..127)
 */
object HidDescriptor {

    const val REPORT_ID_DIGITIZER: Int = 1
    const val DIGITIZER_REPORT_SIZE: Int = 7

    const val REPORT_ID_MOUSE: Int = 2
    const val MOUSE_REPORT_SIZE: Int = 4

    const val REPORT_ID_KEYBOARD: Int = 3
    const val KEYBOARD_REPORT_SIZE: Int = 8  // 1 modifier + 1 reserved + 6 keycodes

    // Keyboard modifier bits
    const val MOD_LEFT_CTRL: Int = 0x01
    const val MOD_LEFT_SHIFT: Int = 0x02
    const val MOD_LEFT_ALT: Int = 0x04
    const val MOD_LEFT_GUI: Int = 0x08

    const val X_MAX: Int = 32767
    const val Y_MAX: Int = 32767
    const val PRESSURE_MAX: Int = 4095

    val DESCRIPTOR: ByteArray = bytes(
        // ===== DIGITIZER PEN (Report ID 1) =====
        // Usage Page (Digitizer)
        0x05, 0x0D,
        // Usage (Pen)
        0x09, 0x02,
        // Collection (Application)
        0xA1, 0x01,
        // Report ID (1)
        0x85, 0x01,
        // Usage (Stylus)
        0x09, 0x20,
        // Collection (Physical)
        0xA1, 0x00,

        // --- Buttons: 3 bits ---
        0x09, 0x42,       // Usage (Tip Switch)
        0x09, 0x44,       // Usage (Barrel Switch)
        0x09, 0x32,       // Usage (In Range)
        0x09, 0x45,       // Usage (Eraser)
        0x15, 0x00,       // Logical Minimum (0)
        0x25, 0x01,       // Logical Maximum (1)
        0x75, 0x01,       // Report Size (1)
        0x95, 0x04,       // Report Count (4)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        // --- Padding: 4 bits ---
        0x75, 0x04,       // Report Size (4)
        0x95, 0x01,       // Report Count (1)
        0x81, 0x03,       // Input (Constant)

        // --- X: 16 bits absolute ---
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x30,       // Usage (X)
        0x15, 0x00,       // Logical Minimum (0)
        0x26, 0xFF, 0x7F, // Logical Maximum (32767)
        0x75, 0x10,       // Report Size (16)
        0x95, 0x01,       // Report Count (1)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        // --- Y: 16 bits absolute ---
        0x09, 0x31,       // Usage (Y)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        // --- Pressure: 16 bits ---
        0x05, 0x0D,       // Usage Page (Digitizer)
        0x09, 0x30,       // Usage (Tip Pressure)
        0x26, 0xFF, 0x0F, // Logical Maximum (4095)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        0xC0,             // End Collection (Physical)
        0xC0,             // End Collection (Application)

        // ===== MOUSE (Report ID 2) =====
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x85, 0x02,       // Report ID (2)
        0x09, 0x01,       // Usage (Pointer)
        0xA1, 0x00,       // Collection (Physical)

        // --- Buttons: 3 bits (left, right, middle) ---
        0x05, 0x09,       // Usage Page (Button)
        0x19, 0x01,       // Usage Minimum (Button 1)
        0x29, 0x03,       // Usage Maximum (Button 3)
        0x15, 0x00,       // Logical Minimum (0)
        0x25, 0x01,       // Logical Maximum (1)
        0x75, 0x01,       // Report Size (1)
        0x95, 0x03,       // Report Count (3)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        // --- Padding: 5 bits ---
        0x75, 0x05,       // Report Size (5)
        0x95, 0x01,       // Report Count (1)
        0x81, 0x03,       // Input (Constant)

        // --- X, Y delta: 8 bits relative each ---
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x30,       // Usage (X)
        0x09, 0x31,       // Usage (Y)
        0x15, 0x81,       // Logical Minimum (-127)
        0x25, 0x7F,       // Logical Maximum (127)
        0x75, 0x08,       // Report Size (8)
        0x95, 0x02,       // Report Count (2)
        0x81, 0x06,       // Input (Data, Variable, Relative)

        // --- Scroll wheel: 8 bits relative ---
        0x09, 0x38,       // Usage (Wheel)
        0x15, 0x81,       // Logical Minimum (-127)
        0x25, 0x7F,       // Logical Maximum (127)
        0x75, 0x08,       // Report Size (8)
        0x95, 0x01,       // Report Count (1)
        0x81, 0x06,       // Input (Data, Variable, Relative)

        0xC0,             // End Collection (Physical)
        0xC0,             // End Collection (Application)

        // ===== KEYBOARD (Report ID 3) =====
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, 0x03,       // Report ID (3)

        // --- Modifier keys: 8 bits ---
        0x05, 0x07,       // Usage Page (Keyboard/Keypad)
        0x19, 0xE0,       // Usage Minimum (Left Control)
        0x29, 0xE7,       // Usage Maximum (Right GUI)
        0x15, 0x00,       // Logical Minimum (0)
        0x25, 0x01,       // Logical Maximum (1)
        0x75, 0x01,       // Report Size (1)
        0x95, 0x08,       // Report Count (8)
        0x81, 0x02,       // Input (Data, Variable, Absolute)

        // --- Reserved byte ---
        0x75, 0x08,       // Report Size (8)
        0x95, 0x01,       // Report Count (1)
        0x81, 0x03,       // Input (Constant)

        // --- Key codes: 6 bytes ---
        0x05, 0x07,       // Usage Page (Keyboard/Keypad)
        0x19, 0x00,       // Usage Minimum (0)
        0x29, 0xFF,       // Usage Maximum (255)
        0x15, 0x00,       // Logical Minimum (0)
        0x26, 0xFF, 0x00, // Logical Maximum (255)
        0x75, 0x08,       // Report Size (8)
        0x95, 0x06,       // Report Count (6)
        0x81, 0x00,       // Input (Data, Array)

        0xC0              // End Collection (Application)
    )

    fun buildReport(
        tipDown: Boolean,
        barrel: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int,
        eraser: Boolean = false
    ): ByteArray {
        var buttons = 0
        if (tipDown) buttons = buttons or 0x01
        if (barrel) buttons = buttons or 0x02
        if (inRange) buttons = buttons or 0x04
        if (eraser) buttons = buttons or 0x08

        val cx = x.coerceIn(0, X_MAX)
        val cy = y.coerceIn(0, Y_MAX)
        val cp = pressure.coerceIn(0, PRESSURE_MAX)

        return byteArrayOf(
            buttons.toByte(),
            (cx and 0xFF).toByte(),
            (cx shr 8).toByte(),
            (cy and 0xFF).toByte(),
            (cy shr 8).toByte(),
            (cp and 0xFF).toByte(),
            (cp shr 8).toByte()
        )
    }

    fun buildMouseReport(
        left: Boolean,
        right: Boolean,
        middle: Boolean,
        dx: Int,
        dy: Int,
        scroll: Int = 0
    ): ByteArray {
        var buttons = 0
        if (left) buttons = buttons or 0x01
        if (right) buttons = buttons or 0x02
        if (middle) buttons = buttons or 0x04

        return byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            scroll.coerceIn(-127, 127).toByte()
        )
    }

    fun buildKeyboardReport(modifiers: Int): ByteArray {
        return ByteArray(KEYBOARD_REPORT_SIZE).also {
            it[0] = modifiers.toByte()  // modifier keys
            // bytes 1-7 are 0 (reserved + no key codes)
        }
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
