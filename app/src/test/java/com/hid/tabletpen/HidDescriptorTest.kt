package com.hid.tabletpen

import org.junit.Assert.*
import org.junit.Test

class HidDescriptorTest {

    // ---- buildReport (digitizer) ----

    @Test
    fun `buildReport tip down sets bit 0`() {
        val report = HidDescriptor.buildReport(tipDown = true, barrel = false, inRange = false, 0, 0, 0)
        assertEquals(0x01, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport barrel sets bit 1`() {
        val report = HidDescriptor.buildReport(tipDown = false, barrel = true, inRange = false, 0, 0, 0)
        assertEquals(0x02, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport inRange sets bit 2`() {
        val report = HidDescriptor.buildReport(tipDown = false, barrel = false, inRange = true, 0, 0, 0)
        assertEquals(0x04, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport all buttons combines bits`() {
        val report = HidDescriptor.buildReport(tipDown = true, barrel = true, inRange = true, 0, 0, 0)
        assertEquals(0x07, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport X encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 0x1234, y = 0, pressure = 0)
        assertEquals(0x34, report[1].toInt() and 0xFF) // low byte
        assertEquals(0x12, report[2].toInt() and 0xFF) // high byte
    }

    @Test
    fun `buildReport Y encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 0, y = 0x5678, pressure = 0)
        assertEquals(0x78, report[3].toInt() and 0xFF) // low byte
        assertEquals(0x56, report[4].toInt() and 0xFF) // high byte
    }

    @Test
    fun `buildReport pressure encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, pressure = 0x0ABC)
        assertEquals(0xBC.toByte(), report[5])
        assertEquals(0x0A, report[6].toInt() and 0xFF)
    }

    @Test
    fun `buildReport clamps X to max`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 50000, y = 0, pressure = 0)
        val x = (report[1].toInt() and 0xFF) or ((report[2].toInt() and 0xFF) shl 8)
        assertEquals(HidDescriptor.X_MAX, x)
    }

    @Test
    fun `buildReport clamps negative X to 0`() {
        val report = HidDescriptor.buildReport(false, false, false, x = -100, y = 0, pressure = 0)
        val x = (report[1].toInt() and 0xFF) or ((report[2].toInt() and 0xFF) shl 8)
        assertEquals(0, x)
    }

    @Test
    fun `buildReport clamps pressure to max`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, pressure = 9999)
        val p = (report[5].toInt() and 0xFF) or ((report[6].toInt() and 0xFF) shl 8)
        assertEquals(HidDescriptor.PRESSURE_MAX, p)
    }

    @Test
    fun `buildReport returns 7 bytes`() {
        val report = HidDescriptor.buildReport(true, true, true, 32767, 32767, 4095)
        assertEquals(HidDescriptor.DIGITIZER_REPORT_SIZE, report.size)
    }

    // ---- buildMouseReport ----

    @Test
    fun `buildMouseReport left button sets bit 0`() {
        val report = HidDescriptor.buildMouseReport(left = true, right = false, middle = false, 0, 0)
        assertEquals(0x01, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildMouseReport right button sets bit 1`() {
        val report = HidDescriptor.buildMouseReport(left = false, right = true, middle = false, 0, 0)
        assertEquals(0x02, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildMouseReport clamps dx to 127`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, dx = 500, dy = 0)
        assertEquals(127, report[1].toInt())
    }

    @Test
    fun `buildMouseReport clamps dx to negative 127`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, dx = -500, dy = 0)
        assertEquals(-127, report[1].toInt())
    }

    @Test
    fun `buildMouseReport scroll value`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, 0, 0, scroll = 42)
        assertEquals(42, report[3].toInt())
    }

    @Test
    fun `buildMouseReport returns 4 bytes`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, 0, 0)
        assertEquals(HidDescriptor.MOUSE_REPORT_SIZE, report.size)
    }

    // ---- buildKeyboardReport ----

    @Test
    fun `buildKeyboardReport sets modifier byte`() {
        val report = HidDescriptor.buildKeyboardReport(HidDescriptor.MOD_LEFT_CTRL)
        assertEquals(HidDescriptor.MOD_LEFT_CTRL.toByte(), report[0])
    }

    @Test
    fun `buildKeyboardReport remaining bytes are zero`() {
        val report = HidDescriptor.buildKeyboardReport(0xFF)
        for (i in 1 until report.size) {
            assertEquals("byte $i should be 0", 0, report[i].toInt())
        }
    }

    @Test
    fun `buildKeyboardReport returns 8 bytes`() {
        val report = HidDescriptor.buildKeyboardReport(0)
        assertEquals(HidDescriptor.KEYBOARD_REPORT_SIZE, report.size)
    }

    // ---- DESCRIPTOR ----

    @Test
    fun `descriptor starts with digitizer usage page`() {
        assertEquals(0x05, HidDescriptor.DESCRIPTOR[0].toInt() and 0xFF)
        assertEquals(0x0D, HidDescriptor.DESCRIPTOR[1].toInt() and 0xFF)
    }

    @Test
    fun `descriptor is non-empty`() {
        assertTrue(HidDescriptor.DESCRIPTOR.size > 50)
    }

    // ---- Eraser support ----

    @Test
    fun `buildReport eraser sets bit 3`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, 0, eraser = true)
        assertEquals(0x08, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport eraser combines with tip and inRange`() {
        val report = HidDescriptor.buildReport(true, false, true, 0, 0, 0, eraser = true)
        assertEquals(0x0D, report[0].toInt() and 0xFF) // 0x01 | 0x04 | 0x08
    }

    @Test
    fun `buildReport default eraser is false`() {
        val report = HidDescriptor.buildReport(true, true, true, 0, 0, 0)
        assertEquals(0x07, report[0].toInt() and 0xFF) // no eraser bit
    }

    // ---- Tilt support ----

    @Test
    fun `buildReport returns 11 bytes with tilt`() {
        val report = HidDescriptor.buildReport(true, false, true, 100, 200, 500, tiltX = 50, tiltY = -30)
        assertEquals(HidDescriptor.DIGITIZER_REPORT_SIZE, report.size)
        assertEquals(11, report.size)
    }

    @Test
    fun `buildReport tilt X encoded as little-endian int16`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, 0, tiltX = 100, tiltY = 0)
        assertEquals(100, report[7].toInt() and 0xFF)
        assertEquals(0, report[8].toInt() and 0xFF)
    }

    @Test
    fun `buildReport negative tilt Y`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, 0, tiltX = 0, tiltY = -50)
        // -50 in 16-bit LE = 0xCE, 0xFF
        val ty = (report[9].toInt() and 0xFF) or ((report[10].toInt() and 0xFF) shl 8)
        val signed = if (ty > 32767) ty - 65536 else ty
        assertEquals(-50, signed)
    }

    @Test
    fun `buildReport tilt clamped to max`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, 0, tiltX = 999, tiltY = -999)
        val tx = (report[7].toInt() and 0xFF) or ((report[8].toInt() and 0xFF) shl 8)
        val ty = (report[9].toInt() and 0xFF) or ((report[10].toInt() and 0xFF) shl 8)
        val signedTy = if (ty > 32767) ty - 65536 else ty
        assertEquals(HidDescriptor.TILT_MAX, tx)
        assertEquals(-HidDescriptor.TILT_MAX, signedTy)
    }

    // ---- Keyboard keycodes ----

    @Test
    fun `buildKeyboardReport with keycode`() {
        val report = HidDescriptor.buildKeyboardReport(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_Z)
        assertEquals(HidDescriptor.MOD_LEFT_CTRL.toByte(), report[0])
        assertEquals(0, report[1].toInt()) // reserved
        assertEquals(HidDescriptor.KEY_Z.toByte(), report[2])
    }

    @Test
    fun `buildKeyboardReport with multiple keycodes`() {
        val report = HidDescriptor.buildKeyboardReport(0, HidDescriptor.KEY_Z, HidDescriptor.KEY_Y)
        assertEquals(HidDescriptor.KEY_Z.toByte(), report[2])
        assertEquals(HidDescriptor.KEY_Y.toByte(), report[3])
        assertEquals(0, report[4].toInt()) // no more keys
    }

    @Test
    fun `new keycodes have correct values`() {
        assertEquals(0x16, HidDescriptor.KEY_S)
        assertEquals(0x06, HidDescriptor.KEY_C)
        assertEquals(0x19, HidDescriptor.KEY_V)
        assertEquals(0x1B, HidDescriptor.KEY_X)
        assertEquals(0x04, HidDescriptor.KEY_A)
        assertEquals(0x08, HidDescriptor.KEY_E)
        assertEquals(0x05, HidDescriptor.KEY_B)
        assertEquals(0x2C, HidDescriptor.KEY_SPACE)
        assertEquals(0x2B, HidDescriptor.KEY_TAB)
    }

    @Test
    fun `buildKeyboardReport Ctrl+Z for undo`() {
        val report = HidDescriptor.buildKeyboardReport(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_Z)
        assertEquals(HidDescriptor.MOD_LEFT_CTRL.toByte(), report[0])
        assertEquals(HidDescriptor.KEY_Z.toByte(), report[2])
    }

    @Test
    fun `buildKeyboardReport Ctrl+Shift+Z for redo`() {
        val mods = HidDescriptor.MOD_LEFT_CTRL or HidDescriptor.MOD_LEFT_SHIFT
        val report = HidDescriptor.buildKeyboardReport(mods, HidDescriptor.KEY_Z)
        assertEquals(mods.toByte(), report[0])
        assertEquals(HidDescriptor.KEY_Z.toByte(), report[2])
    }

    @Test
    fun `buildReport with all features combined`() {
        val report = HidDescriptor.buildReport(
            tipDown = true, barrel = true, inRange = true,
            x = 16000, y = 8000, pressure = 2048,
            eraser = true, tiltX = 100, tiltY = -50
        )
        assertEquals(11, report.size)
        assertEquals(0x0F, report[0].toInt() and 0xFF)  // all 4 bits set
    }

    @Test
    fun `buildKeyboardReport max 6 keycodes`() {
        val report = HidDescriptor.buildKeyboardReport(0, 1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(6.toByte(), report[7]) // 7th keycode (index 6+2=8) should NOT be set
        // Actually bytes 2-7 = 6 slots: 1,2,3,4,5,6. 7 and 8 are dropped
        assertEquals(1.toByte(), report[2])
        assertEquals(6.toByte(), report[7])
    }
}
