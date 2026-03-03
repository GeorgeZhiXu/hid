package com.hid.tabletpen

/**
 * HID Report Descriptor for a digitizer pen tablet.
 *
 * Report ID 1 — Digitizer (7 bytes):
 *   Byte 0: [0:Tip Switch][1:Barrel Switch][2:In Range][3-7:padding]
 *   Byte 1-2: X (uint16 LE, 0–32767)
 *   Byte 3-4: Y (uint16 LE, 0–32767)
 *   Byte 5-6: Pressure (uint16 LE, 0–4095)
 */
object HidDescriptor {

    const val REPORT_ID_DIGITIZER: Int = 1
    const val DIGITIZER_REPORT_SIZE: Int = 7

    const val X_MAX: Int = 32767
    const val Y_MAX: Int = 32767
    const val PRESSURE_MAX: Int = 4095

    val DESCRIPTOR: ByteArray = bytes(
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
        // Usage (Tip Switch)
        0x09, 0x42,
        // Usage (Barrel Switch)
        0x09, 0x44,
        // Usage (In Range)
        0x09, 0x32,
        // Logical Minimum (0)
        0x15, 0x00,
        // Logical Maximum (1)
        0x25, 0x01,
        // Report Size (1)
        0x75, 0x01,
        // Report Count (3)
        0x95, 0x03,
        // Input (Data, Variable, Absolute)
        0x81, 0x02,

        // --- Padding: 5 bits ---
        // Report Size (5)
        0x75, 0x05,
        // Report Count (1)
        0x95, 0x01,
        // Input (Constant)
        0x81, 0x03,

        // --- X: 16 bits absolute ---
        // Usage Page (Generic Desktop)
        0x05, 0x01,
        // Usage (X)
        0x09, 0x30,
        // Logical Minimum (0)
        0x15, 0x00,
        // Logical Maximum (32767)
        0x26, 0xFF, 0x7F,
        // Report Size (16)
        0x75, 0x10,
        // Report Count (1)
        0x95, 0x01,
        // Input (Data, Variable, Absolute)
        0x81, 0x02,

        // --- Y: 16 bits absolute ---
        // Usage (Y)
        0x09, 0x31,
        // Input (Data, Variable, Absolute)
        0x81, 0x02,

        // --- Pressure: 16 bits ---
        // Usage Page (Digitizer)
        0x05, 0x0D,
        // Usage (Tip Pressure)
        0x09, 0x30,
        // Logical Maximum (4095)
        0x26, 0xFF, 0x0F,
        // Input (Data, Variable, Absolute)
        0x81, 0x02,

        // End Collection (Physical)
        0xC0,
        // End Collection (Application)
        0xC0
    )

    fun buildReport(
        tipDown: Boolean,
        barrel: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int
    ): ByteArray {
        var buttons = 0
        if (tipDown) buttons = buttons or 0x01
        if (barrel) buttons = buttons or 0x02
        if (inRange) buttons = buttons or 0x04

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

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
